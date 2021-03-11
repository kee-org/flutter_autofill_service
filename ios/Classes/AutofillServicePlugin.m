#import "FlutterAutofillPlugin.h"
#import <flutter_autofill_service/flutter_autofill_service-Swift.h>

@implementation FlutterAutofillPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftFlutterAutofillPlugin registerWithRegistrar:registrar];
}
@end
