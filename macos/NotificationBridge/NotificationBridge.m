#import <AppKit/AppKit.h>
#import <UserNotifications/UserNotifications.h>

@interface CompanionNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end
@implementation CompanionNotificationDelegate
- (void)userNotificationCenter:(UNUserNotificationCenter *)center willPresentNotification:(UNNotification *)notification withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completion {
    completion(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList | UNNotificationPresentationOptionSound);
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center didReceiveNotificationResponse:(UNNotificationResponse *)response withCompletionHandler:(void (^)(void))completion {
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp activateIgnoringOtherApps:YES];
        for (NSWindow *window in NSApp.windows) {
            if (window.canBecomeMainWindow) { [window makeKeyAndOrderFront:nil]; break; }
        }
    });
    completion();
}
@end

static BOOL Await(dispatch_semaphore_t signal, int seconds) {
    return dispatch_semaphore_wait(signal, dispatch_time(DISPATCH_TIME_NOW, seconds * NSEC_PER_SEC)) == 0;
}

// Negative: unavailable/denied, 1: submitted/pending, 2: system delivery confirmed.
int companion_notification_deliver(const char *identifier, const char *title, const char *body) {
    @autoreleasepool {
        if ([NSThread isMainThread]) return -1;
        if (![NSBundle.mainBundle.bundlePath hasSuffix:@".app"]) return -2;
        @try {
            UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
            static CompanionNotificationDelegate *delegate;
            static dispatch_once_t once;
            dispatch_once(&once, ^{ delegate = [CompanionNotificationDelegate new]; });
            center.delegate = delegate;
            NSString *key = [NSString stringWithUTF8String:identifier];
            __block BOOL found = NO;
            dispatch_semaphore_t delivered = dispatch_semaphore_create(0);
            [center getDeliveredNotificationsWithCompletionHandler:^(NSArray<UNNotification *> *items) {
                for (UNNotification *item in items) if ([item.request.identifier isEqualToString:key]) found = YES;
                dispatch_semaphore_signal(delivered);
            }];
            if (!Await(delivered, 5)) return -3;
            if (found) return 2;
            NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
            NSString *submittedKey = [@"companion.notification.submitted." stringByAppendingString:key];
            // Do not repost a notification dismissed before the next poll.
            if ([defaults boolForKey:submittedKey]) return 1;
            __block UNAuthorizationStatus status;
            dispatch_semaphore_t settings = dispatch_semaphore_create(0);
            [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *value) {
                status = value.authorizationStatus; dispatch_semaphore_signal(settings);
            }];
            if (!Await(settings, 5)) return -3;
            if (status == UNAuthorizationStatusNotDetermined) {
                __block BOOL granted = NO;
                dispatch_semaphore_t authorized = dispatch_semaphore_create(0);
                [center requestAuthorizationWithOptions:UNAuthorizationOptionAlert | UNAuthorizationOptionSound completionHandler:^(BOOL allowed, NSError *error) {
                    granted = allowed; dispatch_semaphore_signal(authorized);
                }];
                if (!Await(authorized, 60)) return -3;
                if (!granted) return -4;
            } else if (status == UNAuthorizationStatusDenied) return -4;
            __block BOOL pending = NO;
            dispatch_semaphore_t checked = dispatch_semaphore_create(0);
            [center getPendingNotificationRequestsWithCompletionHandler:^(NSArray<UNNotificationRequest *> *items) {
                for (UNNotificationRequest *item in items) if ([item.identifier isEqualToString:key]) pending = YES;
                dispatch_semaphore_signal(checked);
            }];
            if (!Await(checked, 5)) return -3;
            if (pending) return 1;
            UNMutableNotificationContent *content = [UNMutableNotificationContent new];
            content.title = [NSString stringWithUTF8String:title];
            content.body = [NSString stringWithUTF8String:body];
            content.sound = UNNotificationSound.defaultSound;
            __block BOOL accepted = NO;
            dispatch_semaphore_t submitted = dispatch_semaphore_create(0);
            [center addNotificationRequest:[UNNotificationRequest requestWithIdentifier:key content:content trigger:nil] withCompletionHandler:^(NSError *error) {
                accepted = error == nil; dispatch_semaphore_signal(submitted);
            }];
            if (!Await(submitted, 5)) return -3;
            if (accepted) [defaults setBool:YES forKey:submittedKey];
            return accepted ? 1 : -5;
        } @catch (NSException *exception) { return -6; }
    }
}
