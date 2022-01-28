# flutter_autofill_service

Integrate Flutter with platform autofill services.

Only tested on Android but maybe some iOS features work too. PRs welcome to complete that part of the work. No desktop or web support is planned but again, open an issue or PR if you have any ideas on what form that support might take.

## Features

* Initial autofill match results are blocked behind an authentication step (no automatic reveal of user data without their request and your code's authorisation).
* Then up to 10 match results can be returned to the relevant app (in the example app this is done manually but you'd probably want to automate it).
* A "Choose a different" entry result option allows the user to return to your app to select a different result that was not previously matched to the app or website in question.
* Saving newly supplied data is supported.
* Example app demonstrates all the major features.

## Planned

* Respond to any inaccuracies in matching code algorithms for existing or new Android versions. We don't know if the current behaviour is perfect but suspect there is room for some improved heuristics.
* Investigate IME integration support for Android 11+ (we started this but currently there is an Android bug which prevents the use of IME with an authentication step so are not sure if it is worthwhile proceeding with this alternative approach to Android match presentation to users).
* Test the current status on iOS and decide if it's worth supporting that platform too.
* Find out why Android Autofill examples suggest we handle focussed things in a special way (we follow their guidance and append " (focussed)" to the visible title but rarely see this appear in real usage and we don't understand if there is any notable significance or something extra we should be doing).
* Help library consumers determine if a save request from the user has already been handled or not (currently consumers need to track this themselves and some edge cases involving intentional repeat actions from users may cause a little confusion).
* See if we can release it on the public flutter libraries website.

## Contributing

Please open issues or discussions or PRs as you see fit. We're not expecting a deluge of activity so want to keep everything as informal as possible at least to start with. However, please do take note the CODE_OF_CONDUCT.md - an informal approach does not mean we will tolerate prejudice or abuse.

This project was inspired by and incorporates some code from the autofill_service package by hpoul. In particular, any iOS support in the initial commit is likely to be wholly a result of his efforts!
