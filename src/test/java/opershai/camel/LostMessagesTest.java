package opershai.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.camel.test.spring.junit5.MockEndpoints;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

@CamelSpringBootTest
@EnableAutoConfiguration
@EmbeddedKafka(controlledShutdown = true, partitions = 1)
@MockEndpoints("kafka:outbound-topic")
public class LostMessagesTest {
    private static final Logger LOG = LoggerFactory.getLogger("testLogger");

    @Value("${spring.embedded.kafka.brokers}")
    private String brokerAddresses;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private CamelContext camelContext;

    @EndpointInject("mock:kafka:outbound-topic")
    private MockEndpoint outboundTopicMock;

    private int attemptToProcessSecondMessage = 1;

    @Test
    public void breakOnFirstErrorLostMessages() throws Exception {
        sendMessagesToTopic("inbound-topic", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

        configureKafkaComponent();

        createCamelRoute();

        camelContext.start();

        outboundTopicMock.expectedBodiesReceived("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

        outboundTopicMock.assertIsSatisfied();
    }

    private void configureKafkaComponent() {
        KafkaConfiguration conf = new KafkaConfiguration();
        conf.setBrokers(brokerAddresses);
        conf.setAutoCommitEnable(false);
        conf.setAllowManualCommit(true);
        conf.setBreakOnFirstError(true);
        conf.setAutoOffsetReset("earliest");
        conf.setMaxPollRecords(5);

        KafkaComponent component = new KafkaComponent();
        component.setConfiguration(conf);
        camelContext.addComponent("kafka", component);
    }

    private void createCamelRoute() throws Exception {
        RouteBuilder.addRoutes(camelContext, builder -> builder
                .from("kafka:inbound-topic")
                .process(exchange -> {
                    String message = getMessage(exchange);
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
                .to("kafka:outbound-topic")
                .process(exchange -> {
                    // Manually Commit Offset:
                    String message = getMessage(exchange);
                    LOG.info(message + " - committing offset.");
                    KafkaManualCommit manual = exchange.getIn().getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class);
                    manual.commit();
                    LOG.info(message + " - successfully committed offset.");
                })
        );
    }

    private void sendMessagesToTopic(String topic, String... messages) {
        KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(embeddedKafkaBroker))
        );
        for (String message : messages) {
            LOG.info("Sending message {} to topic {}.", message, topic);
            kafkaTemplate.send(topic, message);
        }
    }

    private String getMessage(Exchange exchange) {
        String offset = exchange.getMessage().getHeader(KafkaConstants.OFFSET, String.class);
        String body = exchange.getMessage().getBody(String.class);
        return "Message " + body + " (offset=" + offset + ")";
    }


}
