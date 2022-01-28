package com.keevault.flutter_autofill_service_example

import io.flutter.embedding.android.FlutterActivity

class AutofillActivity: FlutterActivity() {

    override fun getDartEntrypointFunctionName(): String {
        return "autofillEntryPoint"
    }
}
