% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Examples**

```esql
ROW message = "Some Text" | EVAL message_reversed = REVERSE(message);
```

| message:keyword | message_reversed:keyword |
| --- | --- |
| Some Text | txeT emoS |

`REVERSE` works with unicode, too! It keeps unicode grapheme clusters together during reversal.

```esql
ROW bending_arts = "💧🪨🔥💨" | EVAL bending_arts_reversed = REVERSE(bending_arts);
```

| bending_arts:keyword | bending_arts_reversed:keyword |
| --- | --- |
| 💧🪨🔥💨 | 💨🔥🪨💧 |


