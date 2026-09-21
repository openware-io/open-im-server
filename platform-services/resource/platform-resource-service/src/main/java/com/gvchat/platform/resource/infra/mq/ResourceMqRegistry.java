package com.gvchat.platform.resource.infra.mq;

import com.gvchat.protocol.mq.event.ResourceEventTypes;
import com.gvchat.protocol.mq.group.ResourceMqProducerGroups;
import com.gvchat.protocol.mq.topic.ResourceMqTopics;

/**
 * 资源 MQ Registry 常量登记：event_type → topic → producer group。
 * 实际投递由 {@link EventOutboxRelay} 读取 res_event_outbox PENDING 记录并发布到 RocketMQ。
 */
public final class ResourceMqRegistry {
    public static final String RELEASED_EVENT_TYPE = ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED;
    public static final String CANCELLED_EVENT_TYPE = ResourceEventTypes.RESOURCE_OCCUPATION_CANCELLED;
    public static final String RELEASED_TOPIC = ResourceMqTopics.RESOURCE_OCCUPATION_RELEASED_EVENT;
    public static final String CANCELLED_TOPIC = ResourceMqTopics.RESOURCE_OCCUPATION_CANCELLED_EVENT;
    public static final String PRODUCER_GROUP = ResourceMqProducerGroups.PLATFORM_RESOURCE_SERVICE;

    private ResourceMqRegistry() {
    }
}
