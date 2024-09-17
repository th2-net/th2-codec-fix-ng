# JMH benchmark

encode the following FIX message:

```kotlin
        ParsedMessage(
            MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
            EventId("test_id", "test_book", "test_scope", Instant.now()),
            "ExecutionReport",
            mutableMapOf("encode-mode" to "dirty"),
            PROTOCOL,
            mutableMapOf(
                "header" to mutableMapOf(
                    "MsgSeqNum" to 10947,
                    "SenderCompID" to "SENDER",
                    "SendingTime" to LocalDateTime.parse("2023-04-19T10:36:07.415088"),
                    "TargetCompID" to "RECEIVER",
                    "BeginString" to "FIXT.1.1",
                    "BodyLength" to 295,
                    "MsgType" to "8"
                ),
                "ExecID" to "495504662",
                "ClOrdID" to "zSuNbrBIZyVljs",
                "OrigClOrdID" to "zSuNbrBIZyVljs",
                "OrderID" to "49415882",
                "ExecType" to '0',
                "OrdStatus" to '0',
                "LeavesQty" to BigDecimal(500),
                "CumQty" to BigDecimal(500),
                "SecurityID" to "NWDR",
                "SecurityIDSource" to "8",
                "TradingParty" to mutableMapOf(
                    "NoPartyIDs" to mutableListOf(
                        mutableMapOf(
                            "PartyID" to "NGALL1FX01",
                            "PartyIDSource" to 'D',
                            "PartyRole" to 76
                        ),
                        mutableMapOf(
                            "PartyID" to "0",
                            "PartyIDSource" to 'P',
                            "PartyRole" to 3
                        )
                    )
                ),
                "Account" to "test",
                "OrdType" to 'A',
                "TimeInForce" to '0',
                "Side" to 'B',
                "Symbol" to "ABC",
                "OrderQty" to BigDecimal(500),
                "Price" to BigDecimal(1000),
                "Unknown" to "500",
                "TransactTime" to LocalDateTime.parse("2018-02-05T10:38:08.000008"),
                "trailer" to mutableMapOf(
                    "CheckSum" to "191"
                )
            )
        )
```

decode the same FIX message:

8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=191

Testing is carried out in two formats of parsed messages: String values and Typed values.

## benchmark results for version 0.1.0-dev

dirty mode:

Benchmark                                    Mode  Cnt       Score      Error  Units
FixNgCodecBenchmark.encodeFixMessageString  thrpt   25  178479.225 ± 2851.079  ops/s
FixNgCodecBenchmark.encodeFixMessageTyped   thrpt   25  263077.629 ± 3967.905  ops/s
FixNgCodecBenchmark.parseFixMessageString   thrpt   25  173370.305 ± 1878.013  ops/s
FixNgCodecBenchmark.parseFixMessageTyped    thrpt   25  186232.291 ± 1295.186  ops/s

strict mode:

Benchmark                                    Mode  Cnt       Score      Error  Units
FixNgCodecBenchmark.encodeFixMessageString  thrpt   25  179523.040 ± 3084.493  ops/s
FixNgCodecBenchmark.encodeFixMessageTyped   thrpt   25  265769.868 ± 3893.223  ops/s
FixNgCodecBenchmark.parseFixMessageString   thrpt   25  165978.593 ± 6474.860  ops/s
FixNgCodecBenchmark.parseFixMessageTyped    thrpt   25  186475.155 ± 1548.224  ops/s