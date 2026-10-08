#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>
#import <OpenAICompanionAppShared/OpenAICompanionAppShared.h>

@interface OCAppDelegate : UIResponder <UIApplicationDelegate, UNUserNotificationCenterDelegate>
@end

@implementation OCAppDelegate

#if defined(COMPANION_UI_ACCEPTANCE)
- (BOOL)application:(UIApplication *)application
    shouldAllowExtensionPointIdentifier:(UIApplicationExtensionPointIdentifier)identifier {
    // UI acceptance uses a keyboard XCTest can inspect; normal launches keep extensions.
    if ([identifier isEqualToString:UIApplicationKeyboardExtensionPointIdentifier] &&
        [NSProcessInfo.processInfo.environment[@"COMPANION_ACCEPTANCE_SYSTEM_KEYBOARD"] isEqualToString:@"1"])
        return NO;
    return YES;
}
#endif

- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary<UIApplicationLaunchOptionsKey, id> *)launchOptions {
    [UNUserNotificationCenter currentNotificationCenter].delegate = self;
    return YES;
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionSound);
}

@end

@interface OCSceneDelegate : UIResponder <UIWindowSceneDelegate>
@property (nonatomic, strong) UIWindow *window;
@property (nonatomic, strong) OAICASIosMobileHost *host;
@end

@implementation OCSceneDelegate

- (void)scene:(UIScene *)scene
    willConnectToSession:(UISceneSession *)session
    options:(UISceneConnectionOptions *)connectionOptions {
    if (![scene isKindOfClass:UIWindowScene.class]) return;
    self.host = [OAICASIosMobileHostKt createIosMobileHost];
    self.window = [[UIWindow alloc] initWithWindowScene:(UIWindowScene *)scene];
    self.window.rootViewController = self.host.viewController;
    [self.window makeKeyAndVisible];
    [self.host start];
}

@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(OCAppDelegate.class));
    }
}
