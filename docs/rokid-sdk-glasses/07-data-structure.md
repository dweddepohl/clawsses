# Data Structure - Caps

The `Caps` class is a utility class for serializing and deserializing structured data, supporting multiple basic data types and nested objects. It stores data values using a linked list and provides type-safe access methods.

## Key Features

- Supports multiple data types: booleans, integers, floating-point numbers, strings, binary data, etc.
- Provides serialization and deserialization capabilities
- Offers type-safe access methods

## Data Writing Methods

| Method Signature | Description |
| --- | --- |
| `void write(boolean v)` | Writes a boolean value (internally converted to uint32) |
| `void writeInt32(int v)` | Writes a 32-bit signed integer |
| `void writeUInt32(int v)` | Writes a 32-bit unsigned integer |
| `void writeInt64(long v)` | Writes a 64-bit signed integer |
| `void write(float v)` | Writes a single-precision floating-point number |
| `void write(double v)` | Writes a double-precision floating-point number |
| `void write(String v)` | Writes a string |
| `void write(byte[] data)` | Writes a byte array |
| `void write(Caps obj)` | Writes a nested Caps object |
| `void write(byte[] data, int offset, int length)` | Writes a specified portion of a byte array |

## Data Access Methods

| Method Signature | Description |
| --- | --- |
| `int size()` | Returns the number of stored values |
| `Value at(int idx)` | Retrieves a Value object at the specified index |
| `void clear()` | Clears all stored values |

## Serialization/Deserialization

| Method Signature | Description |
| --- | --- |
| `byte[] serialize()` | Serializes current data into a byte array (native method) |
| `boolean parse(byte[] input, int offset, int length)` | Deserializes data from a byte array (native method) |
| `static Caps fromBytes(byte[] input, int offset, int length)` | Creates a Caps object from a byte array |
| `static Caps fromBytes(byte[] input)` | Creates a Caps object from a byte array (using the entire array) |

## Usage Examples

### Writing Example

```kotlin
// Create a Caps object and populate it with data
val args = Caps()
args.write("sendmessage")      // Write a string
args.writeUInt32(5)            // Write an unsigned 32-bit integer
args.write(true)               // Write a boolean value
args.write(3.14f)              // Write a floating-point number
```

### Reading Data Example

```kotlin
private fun parseCapsValue(value: Caps.Value): String {
    return when (value.type()) {
        Caps.Value.TYPE_STRING -> "String: ${value.getString()}"
        Caps.Value.TYPE_INT32 -> "Int32: ${value.getInt()}"
        Caps.Value.TYPE_UINT32 -> "UInt32: ${value.getInt()}"
        Caps.Value.TYPE_INT64 -> "Int64: ${value.getLong()}"
        Caps.Value.TYPE_UINT64 -> "UInt64: ${value.getLong()}"
        Caps.Value.TYPE_FLOAT -> "Float: ${value.getFloat()}"
        Caps.Value.TYPE_DOUBLE -> "Double: ${value.getDouble()}"
        Caps.Value.TYPE_BINARY -> "Binary: ${value.getBinary().length} bytes"
        Caps.Value.TYPE_OBJECT -> "Caps Object"
        else -> "Unsupported type"
    }
}
```
