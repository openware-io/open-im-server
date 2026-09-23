package io.openware.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.infrastructure.mq.remoting.RemotingMqConsumerFactory;
import io.openware.infrastructure.mq.remoting.RemotingMqProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@EnableConfigurationProperties(MqProperties.class)
public class MqInfrastructureConfig {
  @Bean
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }

  @Bean
  public MqJsonCodec mqJsonCodec() {
    return new MqJsonCodec(objectMapper());
  }

  @Bean(destroyMethod = "close")
  public MqProducer mqProducer(MqProperties properties) throws Exception {
    if (properties.getType() != MqClientType.REMOTING) {
      log.error("Unsupported MQ client type for producer, type={}", properties.getType());
      throw new IllegalStateException("Only Remoting RocketMQ is supported in the current phase.");
    }
    DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
    producer.setNamesrvAddr(properties.getNamesrvAddr());
    producer.start();
    log.info(
        "Initialized RocketMQ producer, type={}, producerGroup={}, namesrvAddr={}",
        properties.getType(),
        properties.getProducerGroup(),
        properties.getNamesrvAddr());
    return new RemotingMqProducer(producer);
  }

  @Bean
  public MqConsumerFactory mqConsumerFactory(MqProperties properties, MqProducer mqProducer) {
    if (properties.getType() != MqClientType.REMOTING) {
      log.error("Unsupported MQ client type for consumer factory, type={}", properties.getType());
      throw new IllegalStateException("Only Remoting RocketMQ is supported in the current phase.");
    }
    log.info(
        "Initialized RocketMQ consumer factory, type={}, consumerGroupPrefix={}, namesrvAddr={}",
        properties.getType(),
        properties.getConsumerGroupPrefix(),
        properties.getNamesrvAddr());
    return new RemotingMqConsumerFactory(properties.getNamesrvAddr(), mqProducer);
  }
}
