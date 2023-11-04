package com.keevault.flutter_autofill_service_example

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity

class AutofillActivity() : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Not sure where we will first be invoked but the logger instance 
        // configuration is immutable across the whole process once first 
        // started so we have to make sure the log location is fixed at
        // this early point.
        System.setProperty("logs.folder", filesDir.absolutePath + "/logs");
        super.onCreate(savedInstanceState)
    }
    
    override fun getDartEntrypointFunctionName(): String {
        return "autofillEntryPoint"
    }
}
