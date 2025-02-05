package com.alibaba.otter.canal.rocketmq;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.AccessChannel;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.RPCHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.otter.canal.common.AbstractMQProducer;
import com.alibaba.otter.canal.common.CanalMessageSerializer;
import com.alibaba.otter.canal.common.MQMessageUtils;
import com.alibaba.otter.canal.common.MQMessageUtils.EntryRowData;
import com.alibaba.otter.canal.common.MQProperties;
import com.alibaba.otter.canal.common.utils.ExecutorTemplate;
import com.alibaba.otter.canal.protocol.FlatMessage;
import com.alibaba.otter.canal.server.exception.CanalServerException;
import com.alibaba.otter.canal.spi.CanalMQProducer;

public class CanalRocketMQProducer extends AbstractMQProducer implements CanalMQProducer {

    private static final Logger logger               = LoggerFactory.getLogger(CanalRocketMQProducer.class);
    private DefaultMQProducer   defaultMQProducer;
    private MQProperties        mqProperties;
    private static final String CLOUD_ACCESS_CHANNEL = "cloud";

    @Override
    public void init(MQProperties rocketMQProperties) {
        super.init(rocketMQProperties);
        this.mqProperties = rocketMQProperties;
        RPCHook rpcHook = null;
        if (rocketMQProperties.getAliyunAccessKey().length() > 0
            && rocketMQProperties.getAliyunSecretKey().length() > 0) {
            SessionCredentials sessionCredentials = new SessionCredentials();
            sessionCredentials.setAccessKey(rocketMQProperties.getAliyunAccessKey());
            sessionCredentials.setSecretKey(rocketMQProperties.getAliyunSecretKey());
            rpcHook = new AclClientRPCHook(sessionCredentials);
        }

        defaultMQProducer = new DefaultMQProducer(rocketMQProperties.getProducerGroup(),
            rpcHook,
            mqProperties.isEnableMessageTrace(),
            mqProperties.getCustomizedTraceTopic());
        if (CLOUD_ACCESS_CHANNEL.equals(rocketMQProperties.getAccessChannel())) {
            defaultMQProducer.setAccessChannel(AccessChannel.CLOUD);
        }
        if (!StringUtils.isEmpty(mqProperties.getNamespace())) {
            defaultMQProducer.setNamespace(mqProperties.getNamespace());
        }
        defaultMQProducer.setNamesrvAddr(rocketMQProperties.getServers());
        defaultMQProducer.setRetryTimesWhenSendFailed(rocketMQProperties.getRetries());
        defaultMQProducer.setVipChannelEnabled(false);
        logger.info("##Start RocketMQ producer##");
        try {
            defaultMQProducer.start();
        } catch (MQClientException ex) {
            throw new CanalServerException("Start RocketMQ producer error", ex);
        }
    }

    @Override
    public void send(final MQProperties.CanalDestination destination, com.alibaba.otter.canal.protocol.Message data,
                     Callback callback) {
        ExecutorTemplate template = new ExecutorTemplate(executor);
        try {
            if (!StringUtils.isEmpty(destination.getDynamicTopic())) {
                // 动态topic
                Map<String, com.alibaba.otter.canal.protocol.Message> messageMap = MQMessageUtils.messageTopics(data,
                    destination.getTopic(),
                    destination.getDynamicTopic());

                for (Map.Entry<String, com.alibaba.otter.canal.protocol.Message> entry : messageMap.entrySet()) {
                    String topicName = entry.getKey().replace('.', '_');
                    com.alibaba.otter.canal.protocol.Message messageSub = entry.getValue();
                    template.submit(new Runnable() {

                        @Override
                        public void run() {
                            try {
                                send(destination, topicName, messageSub);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }

                template.waitForResult();
            } else {
                send(destination, destination.getTopic(), data);
            }

            callback.commit();
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
            callback.rollback();
        } finally {
            template.clear();
        }
    }

    public void send(final MQProperties.CanalDestination destination, String topicName,
                     com.alibaba.otter.canal.protocol.Message message) throws Exception {
        if (!mqProperties.getFlatMessage()) {
            if (destination.getPartitionHash() != null && !destination.getPartitionHash().isEmpty()) {
                // 并发构造
                EntryRowData[] datas = MQMessageUtils.buildMessageData(message, executor);
                // 串行分区
                com.alibaba.otter.canal.protocol.Message[] messages = MQMessageUtils.messagePartition(datas,
                    message.getId(),
                    destination.getPartitionsNum(),
                    destination.getPartitionHash());
                int length = messages.length;

                ExecutorTemplate template = new ExecutorTemplate(executor);
                for (int i = 0; i < length; i++) {
                    com.alibaba.otter.canal.protocol.Message dataPartition = messages[i];
                    if (dataPartition != null) {
                        final int index = i;
                        template.submit(new Runnable() {

                            @Override
                            public void run() {
                                try {
                                    if (logger.isDebugEnabled()) {
                                        logger.debug("flatMessagePart: {}, partition: {}",
                                            JSON.toJSONString(dataPartition, SerializerFeature.WriteMapNullValue),
                                            index);
                                    }
                                    Message data = new Message(topicName,
                                        CanalMessageSerializer.serializer(dataPartition,
                                            mqProperties.isFilterTransactionEntry()));
                                    sendMessage(data, index);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                    }
                }
                // 等所有分片发送完毕
                template.waitForResult();
            } else {
                final int partition = destination.getPartition() != null ? destination.getPartition() : 0;
                Message data = new Message(topicName, CanalMessageSerializer.serializer(message,
                    mqProperties.isFilterTransactionEntry()));
                if (logger.isDebugEnabled()) {
                    logger.debug("send message:{} to destination:{}, partition: {}",
                        message,
                        destination.getCanalDestination(),
                        partition);
                }
                sendMessage(data, partition);
            }
        } else {
            // 并发构造
            EntryRowData[] datas = MQMessageUtils.buildMessageData(message, executor);
            // 串行分区
            List<FlatMessage> flatMessages = MQMessageUtils.messageConverter(datas, message.getId());
            if (flatMessages != null) {
                for (FlatMessage flatMessage : flatMessages) {
                    ExecutorTemplate template = new ExecutorTemplate(executor);
                    if (destination.getPartitionHash() != null && !destination.getPartitionHash().isEmpty()) {
                        FlatMessage[] partitionFlatMessage = MQMessageUtils.messagePartition(flatMessage,
                            destination.getPartitionsNum(),
                            destination.getPartitionHash());
                        int length = partitionFlatMessage.length;
                        for (int i = 0; i < length; i++) {
                            FlatMessage flatMessagePart = partitionFlatMessage[i];
                            if (flatMessagePart != null) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("flatMessagePart: {}, partition: {}",
                                        JSON.toJSONString(flatMessagePart, SerializerFeature.WriteMapNullValue),
                                        i);
                                }
                                final int index = i;
                                template.submit(new Runnable() {

                                    @Override
                                    public void run() {
                                        try {
                                            Message data = new Message(topicName, JSON.toJSONBytes(flatMessagePart,
                                                SerializerFeature.WriteMapNullValue));
                                            sendMessage(data, index);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                });
                            }
                        }

                        // 批量等所有分区的结果
                        template.waitForResult();
                    } else {
                        try {
                            final int partition = destination.getPartition() != null ? destination.getPartition() : 0;
                            if (logger.isDebugEnabled()) {
                                logger.debug("send message: {} to topic: {} fixed partition: {}",
                                    JSON.toJSONString(flatMessage, SerializerFeature.WriteMapNullValue),
                                    topicName,
                                    partition);
                            }
                            Message data = new Message(topicName, JSON.toJSONBytes(flatMessage,
                                SerializerFeature.WriteMapNullValue));
                            sendMessage(data, partition);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("send message to rocket topic: {}", destination.getTopic());
        }
    }

    private void sendMessage(Message message, int partition) throws Exception {
        SendResult sendResult = this.defaultMQProducer.send(message, new MessageQueueSelector() {

            @Override
            public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                if (partition > mqs.size()) {
                    return mqs.get(partition % mqs.size());
                } else {
                    return mqs.get(partition);
                }
            }
        }, null);
        if (logger.isDebugEnabled()) {
            logger.debug("Send Message Result: {}", sendResult);
        }
    }

    @Override
    public void stop() {
        logger.info("## Stop RocketMQ producer##");
        this.defaultMQProducer.shutdown();
        super.stop();
    }
}
