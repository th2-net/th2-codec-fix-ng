# th2-codec-fix-ng 0.1.0

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
charset: US_ASCII
dirtyMode: false
decodeValuesToStrings: true
```

#### beginString
default value: `FIXT.1.1`. Value to put into the `BeginString` field (tag: 8) when encoding messages.

#### dictionary
required value. XML file containing the FIX dictionary.

#### charset
default value: `US_ASCII`. Charset for reading and writing FIX fields.

#### dirtyMode
default value: `false`. If `true`, processes all messages in dirty mode (generates warnings on invalid messages and continues processing). If `false`, only messages that contain the `encode-mode: dirty` property will be processed in dirty mode.

#### decodeValuesToStrings
default value: `true`. Decode all values to strings instead of typed values.

## Release notes
### 0.1.0
  + Dirty mode added. 
  + `dirtyMode` setting option added.
  + `decodeValuesToStrings` setting option added.

### 0.0.1
  + Initial release