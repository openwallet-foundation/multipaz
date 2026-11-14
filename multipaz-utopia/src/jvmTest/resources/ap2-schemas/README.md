# AP2 mandate schemas

The JSON Schemas of the four AP2 mandates and the types they reference, copied
unchanged from the [Agent Payments Protocol](https://github.com/google-agentic-commerce/AP2)
repository, `code/sdk/schemas/ap2/`, at tag `v0.2.0`
(commit `b4587ac1d055888a73b4b21750973cffba961793`). They are licensed under
the Apache License, Version 2.0, like the AP2 repository.

`DelegateMandateSchemaTest` checks `DelegateMandateText` against them, so a
change to a schema that the consent text does not follow fails the test. To
move to a newer AP2 release, copy the files from that release and update this
note.
