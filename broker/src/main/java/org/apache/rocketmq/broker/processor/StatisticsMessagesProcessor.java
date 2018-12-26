/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.broker.processor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.client.consumer.StatisticsMessagesResult;
import org.apache.rocketmq.common.KeyBuilder;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.StatisticsMessagesRequestHeader;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public class StatisticsMessagesProcessor implements NettyRequestProcessor {
    private static final InternalLogger POP_LOGGER = InternalLoggerFactory.getLogger(LoggerName.ROCKETMQ_POP_LOGGER_NAME);
    private final BrokerController brokerController;

    public StatisticsMessagesProcessor(final BrokerController brokerController) {
        this.brokerController = brokerController;
    }
    
    @Override
    public RemotingCommand processRequest(final ChannelHandlerContext ctx, RemotingCommand request) throws RemotingCommandException {
        return processRequest(ctx.channel(), request, true);
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

    private RemotingCommand processRequest(final Channel channel, RemotingCommand request, boolean brokerAllowSuspend) throws RemotingCommandException {
        RemotingCommand response = RemotingCommand.createResponseCommand(null);
        response.setOpaque(request.getOpaque());

        final StatisticsMessagesRequestHeader requestHeader =
            (StatisticsMessagesRequestHeader) request.decodeCommandCustomHeader(StatisticsMessagesRequestHeader.class);
        String topicName = requestHeader.getTopic();
        String consumerGroup = requestHeader.getConsumerGroup();
        long fromTime = requestHeader.getFromTime();
        long toTime = requestHeader.getToTime();

        String remark = "";
        TopicConfig topicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(topicName);
        if (null == topicConfig) {
            remark = "consumeStats, topic config not exist, " + topicName;
            POP_LOGGER.warn(remark);
            response.setCode(ResponseCode.TOPIC_NOT_EXIST);
            response.setRemark(remark);
            return response;
        }
        StatisticsMessagesResult result = new StatisticsMessagesResult();

        getDelayMessages(topicName, consumerGroup, result);
        getMessages(topicName, consumerGroup, topicConfig.getReadQueueNums(), fromTime, toTime, result);

        String retryTopicName = KeyBuilder.buildPopRetryTopic(topicName, consumerGroup);
        TopicConfig retryTopicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(retryTopicName);
        if (retryTopicConfig != null) {
            getMessages(retryTopicName, consumerGroup, retryTopicConfig.getReadQueueNums(), fromTime, toTime, result);
        }
        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        response.setBody(result.encode());
        return response;
    }

    private void getDelayMessages(String topicName, String consumerGroup, StatisticsMessagesResult result) {
        long delayMessages = this.brokerController.getMessageStore().getTimingMessageCount(topicName);
        result.setDelayMessages(result.getDelayMessages() + delayMessages);
    }

    private void getMessages(String topicName, String consumerGroup, int queueNum, long fromTime, long toTime, StatisticsMessagesResult result) {
        long activeMessages = 0;
        long totalMessages = 0;
        for (int i = 0; i < queueNum; i++) {
            long maxOffset;
            if (toTime <= 0) {
                maxOffset = this.brokerController.getMessageStore().getMaxOffsetInQueue(topicName, i);
            } else {
                maxOffset = this.brokerController.getMessageStore().getOffsetInQueueByTime(topicName, i, toTime);
            }

            if (maxOffset < 0) {
                maxOffset = 0;
            }

            long minOffset;
            if (fromTime <= 0) {
                minOffset = this.brokerController.getMessageStore().getMinOffsetInQueue(topicName, i);
            } else {
                minOffset = this.brokerController.getMessageStore().getOffsetInQueueByTime(topicName, i, fromTime);
            }

            if (minOffset < 0) {
                minOffset = 0;
            }
            long consumerOffset = this.brokerController.getConsumerOffsetManager().queryOffset(consumerGroup, topicName, i);
            if (consumerOffset < 0) {
                consumerOffset = minOffset;
            }

            if (consumerOffset < minOffset) {
                consumerOffset = minOffset;
            }
            if (consumerOffset > maxOffset) {
                consumerOffset = maxOffset;
            }

            activeMessages += maxOffset - consumerOffset;
            totalMessages += maxOffset - minOffset;
        }
        result.setActiveMessages(result.getActiveMessages() + activeMessages);
        result.setTotalMessages(result.getTotalMessages() + totalMessages);
    }
}