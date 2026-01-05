# flutter_autofill_service

Integrate Flutter with Android platform autofill services.

## Features

* Initial autofill match results are blocked behind an authentication step (no automatic reveal of user data without their request and your code's authorisation).
* Then up to 10 match results can be returned to the relevant app (in the example app this is done manually but you'd probably want to automate it).
* A "Choose a different" entry result option allows the user to return to your app to select a different result that was not previously matched to the app or website in question.
* Saving newly supplied data is supported.
* IME integration option (Android 12+)
* Detailed logging via tinylog or other slf4j compliant logger 
* Example app demonstrates all the major features.

## Usage

See the example app to understand the API and in particular the `AndroidManifest.xml` file in that project. This file is where you can configure the string and drawable overrides to customise the integration to your project.

Apart from the obvious fake responses and buttons, there is one significant difference between the example app and a real world app:

If your meta-data references a drawable that you do not already reference from elsewhere in your app, AGP will by default exclude it from the release apk/aab build as part of its resource shrinkage process. This is because the resource is only loaded by string name at runtime. To fix this, create (or modify) `android/app/src/main/res/raw/keep.xml` with a `resources` element like this:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_example_drawable_file_name_1_*,@drawable/ic_example_drawable_file_name_2_*"
 />

```

If you'd like to see this demonstrated in a real world example app, take a look at the Kee Vault app - https://github.com/kee-org/keevault2/


## Credential Manager API

The recently launched Android Credential Manager API is the latest way to integrate an AutoFill feature into credential provider apps: https://developer.android.google.cn/identity/sign-in/credential-provider?hl=en

It adds support for transferring PassKeys between your credential provider app and other apps/websites, and also provides app developers with a procedural API they can call to directly link their authentication flow with one or more credential provider apps installed on the device.

We have begun implementing support for the password storage/retrieval part of this new API, including an updated example app.

As a result of the limitations described below, the implementation is untested and may not work in any meaningful way for the foreseeable future. It is possible that a focus on the retrieval aspect of the API could get that working before the ability to save passwords is completed.

### Limitations and differences from the earlier AutoFill API

New credentials entered by the user are available during the onSaveRequest call for the Autofill API but not in the corresponding onBeginCreateCredentialRequest call. Instead, we supply Android with a PendingIntent which it later mutates to add this data before starting the "save" Activity.

Android does not consider the save process to have finished until the "save" Activity it started from the PendingIntent we supply has also finished. This means we cannot use the previous AutoFill API approach of connecting to (or starting) the main application instance. Thus, task switching after saving a new password is likely to result in the user seeing inconsistent data, due to a mismatch between the in-memory representation in the long-running main app instance and the on-disk representation you have created during the save Activity. The solution to this is to require a background synchronisation feature which can keep all the various instances in sync via some co-ordinating service or polling operation.


## Planned

* Respond to any inaccuracies in matching code algorithms for existing or new Android versions. We don't know if the current behaviour is perfect but suspect there is room for some improved heuristics.
* Evaluate the experimental IME integration support for Android 12+ (determine if the Android bug which prevents the use of IME with an authentication step can be worked around or ignored).
* Help library consumers determine if a save request from the user has already been handled or not (currently consumers need to track this themselves and some edge cases involving intentional repeat actions from users may cause a little confusion).
* Continue work on support for the Credential Manager API and maybe Passkeys.

Only Android is supported but maybe desktop or web support can be included one day, open an issue or PR if you have any ideas on what form that support might take. iOS (at least as of v17) does not support autofilling via a Dart/Flutter plugin (it must be a native Extension instead) so there is no support planned for that platform.

## Contributing

Please open issues or discussions or PRs as you see fit. We're not expecting a deluge of activity so want to keep everything as informal as possible at least to start with. However, please do take note the CODE_OF_CONDUCT.md - an informal approach does not mean we will tolerate prejudice or abuse.

This project was inspired by and incorporates some code from the autofill_service package by hpoul.

