# camel-kafka-lost-messages-demo

Project with test demonstrating potential bug when messages are getting lost (skipped processing)
with Camel Kafka component when:
- autoCommitEnable=false
- allowManualCommit=true
- breakOnFirstError=true
- autoOffsetReset=earliest
- maxPollRecords is greater than one
- processing of a particular message failed several times in a raw 

## Versions
* java 17
* Spring boot 2.7.6
* Camel 3.20.0, 3.18.5

## Test Scenario

Kafka component configuration:
- autoCommitEnable=false
- allowManualCommit=true
- breakOnFirstError=true
- autoOffsetReset=earliest
- maxPollRecords=5

Steps:
1. inbound-topic contains 10 messages: 0,1,2,3,4,5,6,7,8,9
2. camel-route polls 5 messages: 0,1,2,3,4
3. camel-route successfully processes message=0 and manually commits offset
4. camel-route successfully processes message=1 and manually commits offset
5. camel-route fails with processing message=2 first time
6. breakOnFirstError=true causes camel-route to reconnect and poll from the committed offset
7. camel-route polls 5 messages: 2,3,4,5,6
8. camel-route fails with processing message=2 second time 
9. *ACTUAL:* camel-route polls messages 7,8,9 - as result messages 3,4,5,6 are never processed

*EXPECTED:* camel-route should do the following on the step 9:
- poll the same 5 messages again: 2,3,4,5,6
- process message=2 third time (the test is configured to succeed on the 3rd attempt)
- continue with processing messages 3,4,5,6,7,8,9