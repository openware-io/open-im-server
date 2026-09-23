package io.openware.platform.marketing.infra.mq;

import io.openware.protocol.mq.event.MktEventTypes;
import io.openware.protocol.mq.group.MktMqProducerGroups;
import io.openware.protocol.mq.topic.MktMqTopics;

/**
 * 营销 MQ Registry 常量登记：event_type → topic → producer group。
 * 实际投递由 {@link EventOutboxRelay} 读取 mkt_event_outbox PENDING 记录并发布到 RocketMQ。
 */
public final class CouponMqRegistry {
    public static final String EVENT_TYPE = MktEventTypes.COUPON_REDEEMED;
    public static final String TOPIC = MktMqTopics.COUPON_REDEEMED_EVENT;
    public static final String PRODUCER_GROUP = MktMqProducerGroups.PLATFORM_MARKETING_SERVICE;

    private CouponMqRegistry() {
    }
}
