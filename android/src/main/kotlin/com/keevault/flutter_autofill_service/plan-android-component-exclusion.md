# Plan: make the `android` exclusion effective

Status: under consideration

## Problem

`android` is the first entry of the plugin's `builtInExcludedPackages` (`FlutterAutofillService.kt`). It is also in consuming app's
`_alwaysExcludedAppIds` (`lib/src/autofill/autofill_target.dart`). Neither check can ever match:

1. `AssistStructureParser.normaliseParsedStructure()` sets `componentPackage` to `null` when it is `android` and removes
   `android` from `nodeIdPackages` and `titlePackages`. This runs in the parser's `init`.
2. `onFillRequest` calls `isExcluded(parser, excludedPackages)` afterwards. That reads the already normalised
   `componentPackage`, `nodeIdPackages` and `titlePackages`, so `android` is never seen.
3. `appId` then falls back to a view-node package (source `viewNode`) or the window title. A request whose activity
   component is the framework package (activities and dialogs hosted by `android`, such as the resolver and chooser) is
   therefore offered to the user as an untrusted "Reported by app" target instead of being declined.

This isn't exploitable. The target is unverified, but it contradicts the intended exclusion.

## Constraint: don't exclude `android` view-node IDs

Ordinary apps use framework resource IDs (`android.R.id.*`), whose `idPackage` is `android`. That is why the parser
removes `android` from `nodeIdPackages`. The fix must look at the **system-supplied activity component only**.
Excluding on an `android` view-node ID would decline requests from almost every app.

## Steps

1. **Keep the raw component.** In `AssistStructureParser`, add
   `val systemComponentPackage: String? = structure.activityComponent?.packageName`, set before normalisation and never
   changed. Its KDoc says it is for exclusion checks only, never for identity (`componentPackage`/`appId` keep today's
   behaviour).
2. **Check it in `isExcluded`.** Change `isExcluded(parser, excluded)` to pass `parser.systemComponentPackage` as the
   component argument. Keep the node and title checks on the normalised sets, so `android` there still doesn't
   exclude.
3. **Order stays the same.** `isExcluded` already runs before any response, including the save-only one. No change
   needed.
4. **Tests** (`android/src/test/.../FlutterAutofillServiceExclusionTest.kt`, `AssistStructureParserDalTest.kt`):
   - Component `android` is excluded. Build the parser with the existing `Fixture.parser("android", ...)` and assert
     `isExcluded(parser, buildExcludedPackages(own, null))`.
   - A normal component whose view nodes use `idPackage = "android"` is **not** excluded.
   - Keep `excludedComponentIsTreatedAsNoComponent` and the test asserting `appId == "com.bank.app"` with
     `appIdTrusted == false` for component `android`. Parser identity behaviour doesn't change, and those tests now
     document what happens if a consumer removes `android` from the exclusions.
   - A consumer-supplied `EXCLUDED_PACKAGES` entry matching the raw component is excluded (regression guard for the
     new path).
5. **Docs and changelog** (plugin): add a CHANGELOG entry ("requests whose activity component is the Android framework
   package are now declined as the built-in exclusion list always intended"). The README exclusion section says that
   exclusion uses the system-supplied component, not view-node IDs.

## Risk

Any request whose activity component is `android` is now declined. That is the documented intent of the built-in list,
but it is a behaviour change for all consumers, so it goes in the changelog. Rollback means removing `android` from
`builtInExcludedPackages`; there's no data or migration impact.
