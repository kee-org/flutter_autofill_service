# flutter_autofill_service

Integrate Flutter with Android platform autofill services.

## Features

* Initial autofill match results are blocked behind an authentication step (no automatic reveal of user data without their request and your code's authorisation).
* Then up to 10 match results can be returned to the relevant app (in the example app this is done manually but you'd probably want to automate it).
* A "Choose a different" entry result option allows the user to return to your app to select a different result that was not previously matched to the app or website in question.
* Saving newly supplied data is supported.
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

## Planned

* Respond to any inaccuracies in matching code algorithms for existing or new Android versions. We don't know if the current behaviour is perfect but suspect there is room for some improved heuristics.
* Investigate IME integration support for Android 11+ (we started this but currently there is an Android bug which prevents the use of IME with an authentication step so are not sure if it is worthwhile proceeding with this alternative approach to Android match presentation to users).
* Find out why Android Autofill examples suggest we handle focussed things in a special way (we follow their guidance and append " (focussed)" to the visible title but rarely see this appear in real usage and we don't understand if there is any notable significance or something extra we should be doing).
* Help library consumers determine if a save request from the user has already been handled or not (currently consumers need to track this themselves and some edge cases involving intentional repeat actions from users may cause a little confusion).

Only Android is supported but maybe desktop or web support can be included one day, open an issue or PR if you have any ideas on what form that support might take. iOS (at least as of v15) does not support autofilling via a Dart/Flutter plugin (it must be a native Extension instead) so there is no support planned for that platform.

## Contributing

Please open issues or discussions or PRs as you see fit. We're not expecting a deluge of activity so want to keep everything as informal as possible at least to start with. However, please do take note the CODE_OF_CONDUCT.md - an informal approach does not mean we will tolerate prejudice or abuse.

This project was inspired by and incorporates some code from the autofill_service package by hpoul.
