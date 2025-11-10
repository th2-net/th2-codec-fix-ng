# th2-codec-fix-ng 0.1.8

This codec can be used in dirty mode for decoding and encoding messages via the FIX protocol.

## Configuration

### Configuration parameters
Configuration example.
```yaml
beginString: FIXT.1.1
dictionary: "${dictionary_link:fix50-generic}"
charset: US-ASCII
decodeDelimiter: "\u0001"
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
default value: `"\u0001"`. Delimiter character from `US-ASCII` charset.

#### dirtyMode
default value: `false`. If `true`, processes all messages in dirty mode (generates warnings on invalid messages and continues processing). If `false`, only messages that contain the `encode-mode: dirty` property will be processed in dirty mode.

#### decodeValuesToStrings
default value: `true`. If `true`, decodes all values to strings instead of typed values.

#### decodeComponentsToNestedMaps
default value: `true`. If `true`, decodes `components` to nested maps instead of unwrap component's map to message's main map.

### th2 schema example

```yaml
apiVersion: th2.exactpro.com/v2
kind: Th2Box
metadata:
  name: codec-fix-ng
spec:
  disabled: false
  imageName: ghcr.io/th2-net/th2-codec-fix-ng
  imageVersion: 0.1.4-dev
  type: th2-codec
  customConfig:
    transportLines:
      default:
        type: TH2_TRANSPORT
        useParentEventId: true
      rpt:
        type: TH2_TRANSPORT
        useParentEventId: true
      lw:
        type: TH2_TRANSPORT
        useParentEventId: true
    codecSettings:
      beginString: FIXT.1.1 # Optional: default "FIXT.1.1"
      dictionary: "${dictionary_link:fix50-generic}" # Required
      charset: US-ASCII # Optional: default "US-ASCII" 
      decodeDelimiter: "\u0001" # Optional: default "\u0001"
      dirtyMode: false # Optional: default false
      decodeValuesToStrings: true # Optional: default true
      decodeComponentsToNestedMaps: true # Optional: default true
  extendedSettings:
    envVariables:
      JAVA_TOOL_OPTIONS: >
        -XX:+ExitOnOutOfMemoryError
        -XX:+UseContainerSupport
        -Dlog4j2.shutdownHookEnabled=false
        -Xlog:gc,gc+heap*,gc+start,gc+metaspace::utc,level,tags
        -XX:MaxRAMPercentage=45.4
        -XX:MaxMetaspaceSize=81M
        -XX:CompressedClassSpaceSize=12M
        -XX:ReservedCodeCacheSize=30M
        -XX:MaxDirectMemorySize=50M
    resources:
      limits:
        memory: 350Mi
        cpu: 300m
      requests:
        memory: 250Mi
        cpu: 200m
    service:
      enabled: false
  pins:
    mq:
      subscribers:
        # default:
        - name: in_codec_encode
          attributes: [subscribe, transport-group, default_encoder_in]
        - name: in_codec_decode
          attributes: [subscribe, transport-group, default_decoder_in]
        # rpt:
        - name: in_codec_rpt_decode
          attributes: [subscribe, transport-group, rpt_decoder_in]
        - name: in_codec_rpt_encode
          attributes: [subscribe, transport-group, rpt_encoder_in]
        # lw:
        - name: in_codec_lw_decode
          attributes: [subscribe, transport-group, lw_decoder_in]
        - name: in_codec_lw_encode
          attributes: [subscribe, transport-group, lw_encoder_in]
      publishers:
        # default:
        - name: out_codec_encode
          attributes: [publish, transport-group, default_encoder_out]
        - name: out_codec_decode
          attributes: [publish, transport-group, default_decoder_out]
        # rpt:
        - name: out_codec_rpt_decode
          attributes: [publish, transport-group, rpt_decoder_out]
        - name: out_codec_rpt_encode
          attributes: [publish, transport-group, rpt_encoder_out]
        # lw:
        - name: out_codec_lw_decode
          attributes: [publish, transport-group, lw_decoder_out]
        - name: out_codec_lw_encode
          attributes: [publish, transport-group, lw_encoder_out]
```

## Performance
Component benchmark results available [here](docs/benchmarks/jmh-benchmark.md).

## Release notes

### 0.1.8

+ Produce multi-platform docker image
  + migrated to [amazoncorretto:11-alpine-jdk](https://hub.docker.com/layers/library/amazoncorretto/11-alpine-jdk) docker image as base
+ Updated:
  + th2 gradle plugin: `0.3.10` (bom: `4.14.2`)
  + th2 codec: `5.6.1-dev`
  + kotlin: `2.2.21`

### 0.1.7

+ Fixes:
  + [[GH-71] Truncates trailed zeros in sub second part for local date time fields](https://github.com/th2-net/th2-codec-fix-ng/issues/71)

### 0.1.6

+ Fixes: 
  + [[GH-68] encoded typed value without error for STRING filed](https://github.com/th2-net/th2-codec-fix-ng/issues/68)
+ Updated:
  + th2 gradle plugin: `0.3.8` (bom: `4.14.1`)
  + kotlin: `2.2.10`
  + kotlin-logging: `7.0.13`
  + common: `5.16.1-dev`
  + codec: `5.6.0-dev`

### 0.1.5

+ Fixes:
  + [Codec can't encode native fix boolean values: Y, N](https://github.com/th2-net/th2-codec-fix-ng/issues/43)
+ Updated:
  + kotlin: `2.2.0`
  + kotlin-logging: `7.0.7`
  + commons-beanutils: `1.11.0`

### 0.1.4

+ Fixes: 
  + codec checks:
    + that tag value may not contain leading zeros.
    + BodyLength field.
    * CheckSum filed.

### 0.1.3
+ Updated:
  + sailfish: `3.4.260`
  + kotlin-logging: `7.0.6`
+ Updated gradle plugins:
  + th2 plugin `0.2.4` (bom: `4.11.0`)
  + kotlin: `2.1.20`
  + jmh: `0.7.3`

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