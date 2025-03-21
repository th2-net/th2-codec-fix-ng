# th2-codec-fix-ng 0.1.2

This codec can be used in dirty mode for decoding and encoding messages via the FIX protocol.

## Configuration

### Codec factory

To use the FIX codec you will need to specify the following codec factory:
**com.exactpro.th2.codec.fixng.FixNgCodecFactory**

### Configuration parameters
Configuration example.
```yaml
beginString: FIXT.1.1
dictionary: fix_dictionary.xml
charset: US-ASCII
decodeDelimiter: \u0001
dirtyMode: false
decodeValuesToStrings: true
decodeComponentsToNestedMaps: true
```

#### beginString
default value: `FIXT.1.1`. Value to put into the `BeginString` field (tag: 8) when encoding messages.

#### dictionary
required value. XML file containing the FIX dictionary.

#### charset
default value: `US-ASCII`. Charset for reading and writing FIX fields.

#### decodeDelimiter
default value: `\u0001`. Delimiter character from `US-ASCII` charset.

#### dirtyMode
default value: `false`. If `true`, processes all messages in dirty mode (generates warnings on invalid messages and continues processing). If `false`, only messages that contain the `encode-mode: dirty` property will be processed in dirty mode.

#### decodeValuesToStrings
default value: `true`. If `true`, decodes all values to strings instead of typed values.

#### decodeComponentsToNestedMaps
default value: `true`. If `true`, decodes `components` to nested maps instead of unwrap component's map to message's main map.

## Performance
Component benchmark results available [here](docs/benchmarks/jmh-benchmark.md).

## Release notes

### next

+ Updated th2 gradle plugin `0.2.2` (bom: `4.10.0`)
  + Added suppressions:
    + CVE-2025-25193 - This vulnerability does not affect us because the component don’t use netty for reading environment files, only for network interaction.
    + CVE-2025-24970 - The component doesn’t directly use SSL for communication between each other  because they are deployed in cluster.

### 0.1.2
  + fixed: codec can't encode fields with type `LocalDateTime`, `LocalDate`, `LocalTime` and value with timezone 
  + Updated sailfish: `3.4.260`

### 0.1.1
  + `decodeDelimiter` setting option added.
  + Updated th2 gradle plugin `0.1.6` (th2-bom: `4.9.0`)

### 0.1.0
  + Dirty mode added. 
  + `dirtyMode` setting option added.
  + `decodeValuesToStrings` setting option added.
  + JMH benchmarks added
  + Migrate to th2 gradle plugin `0.1.2` (th2-bom: `4.7.0`)
  + Updated th2-common: `5.11.0-dev`
  + Updated th2-codec: `5.5.0-dev`
  + Updated sailfish: `3.3.241`
  + Workflows updated

### 0.0.1
  + Initial release