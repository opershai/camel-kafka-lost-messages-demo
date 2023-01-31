package opershai.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.KafkaManualCommit;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spring.javaconfig.SingleRouteCamelConfiguration;
import org.apache.camel.test.spring.CamelSpringRunner;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.rule.EmbeddedKafkaRule;
import org.springframework.kafka.test.utils.KafkaTestUtils;

@RunWith(CamelSpringRunner.class)
public class LostMessagesTest {
    private static final Logger LOG = LoggerFactory.getLogger("testLogger");

    @ClassRule
    public static EmbeddedKafkaRule embeddedKafka = new EmbeddedKafkaRule(1, true);

    @EndpointInject(uri = "mock:kafka:outbound-topic")
    protected MockEndpoint outboundTopicMock;

    @Test
    public void breakOnFirstErrorLostMessages() throws Exception {
        sendMessagesToTopic("inbound-topic", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

        outboundTopicMock.expectedBodiesReceived("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

        outboundTopicMock.assertIsSatisfied();
    }


    private void sendMessagesToTopic(String topic, String... messages) {
        KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(embeddedKafka.getEmbeddedKafka()))
        );
        for (String message : messages) {
            LOG.info("Sending message {} to topic {}.", message, topic);
            kafkaTemplate.send(topic, message);
        }
    }

    @Configuration
    public static class ContextConfig extends SingleRouteCamelConfiguration {

        int attemptToProcessSecondMessage = 1;

        @Bean
        @Autowired
        public KafkaComponent configureKafkaComponent(CamelContext camelContext) {
            KafkaConfiguration conf = new KafkaConfiguration();
            conf.setBrokers(embeddedKafka.getEmbeddedKafka().getBrokersAsString());
            conf.setAutoCommitEnable(false);
            conf.setAllowManualCommit(true);
            conf.setBreakOnFirstError(true);
            conf.setAutoOffsetReset("earliest");
            conf.setMaxPollRecords(5);
            conf.setPollTimeoutMs(1000L);

            KafkaComponent component = new KafkaComponent();
            component.setConfiguration(conf);
            component.setBreakOnFirstError(true);
            component.setAllowManualCommit(true);
            camelContext.addComponent("kafka", component);

            return component;
        }

        public RouteBuilder route() {
            return new RouteBuilder() {
                public void configure() {
                    from("kafka:inbound-topic")
                            .process(exchange -> {
                                String message = getMessageBody(exchange);
                                LOG.info(message + " - start processing.");
                                // Fail first and second attempts to process 2nd message:
                                if ("2".equals(exchange.getMessage().getBody()) && attemptToProcessSecondMessage <= 2) {
                                    String errorMsg = message + " - failed processing " + attemptToProcessSecondMessage + " time.";
                                    LOG.info(errorMsg);
                                    attemptToProcessSecondMessage++;
                                    throw new RuntimeException(errorMsg);
                                }
                                LOG.info(message + " - successfully processed.");
                            })
                            .to("mock:kafka:outbound-topic")
                            .process(exchange -> {
                                // Manually Commit Offset:
                                String message = getMessageBody(exchange);
                                LOG.info(message + " - committing offset.");
                                KafkaManualCommit manual = exchange.getIn().getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class);
                                manual.commitSync();
                                LOG.info(message + " - successfully committed offset.");
                            });
                }
            };
        }

        private static String getMessageBody(Exchange exchange) {
            String offset = exchange.getMessage().getHeader(KafkaConstants.OFFSET, String.class);
            String body = exchange.getMessage().getBody(String.class);
            return "Message " + body + " (offset=" + offset + ")";
        }
    }

}
