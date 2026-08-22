# RFIDGear `.rfPrj` runtime contract

This document records the parts of the RFIDGear project format that are confirmed from the current `c3rebro/RFiDGear` source and therefore form the compatibility contract for the Android encoder.

## Container

RFIDGear recognizes `.rfprj` case-insensitively. New projects are written as ZIP archives. The archive contains `taskdatabase.xml`, serialized with .NET `XmlSerializer` as UTF-8 XML.

Plain `.xml` project files are also accepted by the desktop application and are supported by the Android loader for diagnostics and compatibility testing.

The Android runtime never extracts archive entries onto the filesystem. It reads `taskdatabase.xml` directly from the ZIP stream and applies explicit size/entry limits.

## Root model

The serialized root type is `ChipTaskHandlerModel`.

Relevant root fields:

- `ManifestVersion`: RFIDGear assembly version at project creation time.
- `TaskCollection`: ordered polymorphic task collection.

The desktop application currently uses the application version as a manifest compatibility number by removing dots and comparing numerically. This is not a true independent schema version. Android therefore records the manifest version but does not yet reject a project solely because of this number.

## Polymorphic task collection

`TaskCollection` is an `ObservableCollection<object>` serialized by .NET `XmlSerializer`. Task elements can therefore use `xsi:type` (commonly on an `anyType` element). The Android parser resolves `xsi:type` and does not rely on the element name alone.

`ChipTaskHandlerModel` explicitly includes these types in the current RFIDGear source:

- `MifareDesfireSetupViewModel`
- `MifareClassicSetupViewModel`
- `CommonTaskViewModel`
- `GenericChipTaskViewModel`

RFIDGear also contains `MifareUltralightSetupViewModel` and a `TaskType_MifareUltralightTask` enum, but `ChipTaskHandlerModel` does not currently list the Ultralight view model in its `XmlInclude` attributes. Android recognizes the type name but treats desktop serialization compatibility as an open item until verified with a real RFIDGear-generated project.

## Common task control contract

All executable RFIDGear tasks implement `IGenericTask`. The persisted control fields relevant to Android are:

- `CurrentTaskIndex`: stable task ID stored as a string.
- `SelectedExecuteConditionErrorLevel`: required error result of another task; `Empty` means unconditional execution.
- `SelectedExecuteConditionTaskIndex`: stable task ID referenced by the condition.
- `SelectedTaskType`: operation to execute for the concrete task family.
- `SelectedTaskDescription`: operator-facing description when present.

Runtime-only desktop properties such as task completion state, attempt results, validation flags and dispatcher/UI state are not part of the Android project model.

### Important: position vs task ID

RFIDGear executes `TaskCollection` by collection position. `CurrentTaskIndex` is not used as an array index; it becomes the descriptor ID. Conditional references are resolved by finding the descriptor whose ID equals `SelectedExecuteConditionTaskIndex` and then using that descriptor's collection position.

Therefore the Android runtime must preserve collection order and must reject duplicate stable task IDs.

### Conditional execution

The confirmed desktop rule is:

1. If `SelectedExecuteConditionErrorLevel == Empty`, execute the task.
2. Otherwise resolve `SelectedExecuteConditionTaskIndex` as a stable task ID.
3. Execute only if that referenced task's `CurrentTaskErrorLevel` exactly equals the configured condition error.
4. If the condition does not match, advance to the next collection position.

Forward references are technically representable but normally cannot have the expected non-empty result yet. Android reports them as warnings.

## Task type inventory

### Generic

`TaskType_GenericChipTask`:

- `None`
- `ChipIsOfType`
- `ChipIsMultiChip`
- `CheckUID`
- `ChangeDefault`

### Common

`TaskType_CommonTask`:

- `None`
- `CreateReport`
- `CheckLogicCondition`
- `ExecuteProgram`
- `ChangeDefault`

Desktop-only common operations such as launching an external program will not automatically be available on Android. They require an explicit Android compatibility decision.

### MIFARE Classic

`TaskType_MifareClassicTask`:

- `None`
- `ReadData`
- `WriteData`
- `EmptyCheck`
- `ChangeDefault`

### MIFARE Ultralight

`TaskType_MifareUltralightTask`:

- `None`
- `ReadData`
- `WriteData`
- `ChangeDefault`

### MIFARE DESFire

`TaskType_MifareDesfireTask`:

- `None`
- `FormatDesfireCard`
- `PICCMasterKeyChangeover`
- `PICCMasterKeySettingsChangeover`
- `CreateApplication`
- `DeleteApplication`
- `ApplicationKeyChangeover`
- `ApplicationKeySettingsChangeover`
- `CreateFile`
- `ChangeFileSettings`
- `DeleteFile`
- `ReadData`
- `WriteData`
- `AppExistCheck`
- `AuthenticateApplication`
- `ReadAppSettings`
- `CheckAppKeyCount`
- `ChangeDefault`

## Legacy normalization

The current RFIDGear desktop loader normalizes the legacy error value `AuthenticationError` to `AuthFailure` before XML deserialization. The Android loader performs the same normalization.

## Unknown/future fields

The Android project model intentionally stores the complete element/attribute tree of each task instead of deserializing directly into Android UI classes. Unknown task fields therefore remain accessible to future task-family mappers without changing the container parser.

The representation preserves element names, namespaces, attributes, child order and scalar text. It is intended for runtime interpretation, not byte-for-byte round-tripping of the original XML.

## Security limits

Project files are inputs to a card-encoding tool and must be treated as untrusted configuration. The Android loader currently:

- limits overall container size;
- limits XML size;
- limits ZIP entry count;
- reads archive entries in-memory without filesystem extraction;
- requires `taskdatabase.xml` in `.rfPrj` archives;
- rejects `DOCTYPE` and `ENTITY` declarations;
- disables external XML entities/DTDs where supported by the XML provider.

Secrets contained in project tasks must not be printed in project summaries or normal application logs.

## Next compatibility work

1. Generate representative `.rfPrj` fixtures with the current RFIDGear version for every task family.
2. Commit sanitized fixtures that contain test keys/data only.
3. Map each concrete task's persisted fields into platform-neutral runtime commands.
4. Build a compatibility matrix: RFIDGear operation -> Android supported/unsupported -> liblogicalaccess primitive.
5. Reproduce RFIDGear error semantics and error-routing behavior.
6. Add execution dry-run support before enabling destructive card operations.
