# camel-kafka-lost-messages-demo

Project with test demonstrating issue in [(CAMEL-18985) camel-kafka: messages are getting lost with "breakOnFirstError"](https://issues.apache.org/jira/browse/CAMEL-18985)

## Description
Messages are getting lost with "breakOnFirstError=true" when processing of a particular message failed several times in a raw.

## Versions (fail)
* java 17
* Spring boot 2.7.7
* Camel 3.20.1, 3.20.0, 3.19.0, 3.18.5
* 
## Versions (pass)
* java 8
* Spring Boot 2.1.6.RELEASE
* Camel 2.24.0

## Test Scenario

Kafka component configuration:
- autoCommitEnable=false
- allowManualCommit=true
- breakOnFirstError=true
- autoOffsetReset=earliest
- maxPollRecords is greater than one (e.g. 5 in this test)

Steps:
1. inbound-topic contains 10 messages: 0,1,2,3,4,5,6,7,8,9
2. camel-route polls 5 messages: 0,1,2,3,4
3. camel-route successfully processes message=0 and manually commits offset
4. camel-route successfully processes message=1 and manually commits offset
5. camel-route fails with processing message=2 first time
6. breakOnFirstError=true causes camel-route to reconnect and poll from the committed offset
7. camel-route polls 5 messages: 2,3,4,5,6
8. camel-route fails with processing message=2 second time 
9. *ACTUAL:* camel-route reconnects and polls messages 7,8,9 - as result messages 3,4,5,6 are never processed

*EXPECTED:* camel-route should do the following on the step 9:
- reconnect and poll the same 5 messages again: 2,3,4,5,6
- process message=2 third time (the test is configured to succeed on the 3rd attempt)
- continue with processing messages 3,4,5,6...