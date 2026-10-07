#import <Foundation/Foundation.h>
#import <EventKit/EventKit.h>
#import <AppKit/AppKit.h>

// Owned UTF-8 response. No Objective-C objects cross the JNA boundary.
static char *Reply(NSDictionary *value) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
    if (!data) return strdup("{\"error\":\"SERVER_INTERNAL_ERROR\",\"message\":\"Calendar serialization failed\"}");
    return strdup([[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String);
}
static char *Denied(NSString *message) {
    return Reply(@{@"error": @"PERMISSION_DENIED", @"message": message});
}

char *companion_calendar_query(const char *request) {
    @autoreleasepool {
        if ([NSThread isMainThread]) return Reply(@{@"error": @"SERVER_INTERNAL_ERROR", @"message": @"Call calendar bridge off the main thread"});
        NSDictionary *args = [NSJSONSerialization JSONObjectWithData:[[NSString stringWithUTF8String:request] dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        if (![args isKindOfClass:[NSDictionary class]]) return Reply(@{@"error": @"INVALID_ARGUMENTS"});
        EKEventStore *store = [[EKEventStore alloc] init];
        EKAuthorizationStatus status = [EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent];
        BOOL checkOnly = [args[@"checkOnly"] boolValue];
        NSString *operation = args[@"operation"] ?: @"query";
        if (status == EKAuthorizationStatusNotDetermined && !checkOnly && ![operation isEqualToString:@"create"] && ![operation isEqualToString:@"recover"]) {
            if (![[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSCalendarsFullAccessUsageDescription"])
                return Denied(@"Run the packaged OpenAICompanion.app to grant Calendar access");
            dispatch_semaphore_t completed = dispatch_semaphore_create(0);
            dispatch_async(dispatch_get_main_queue(), ^{
                if (@available(macOS 14.0, *)) {
                    [store requestFullAccessToEventsWithCompletion:^(BOOL granted, NSError *error) { dispatch_semaphore_signal(completed); }];
                } else {
                    [store requestAccessToEntityType:EKEntityTypeEvent completion:^(BOOL granted, NSError *error) { dispatch_semaphore_signal(completed); }];
                }
            });
            if (dispatch_semaphore_wait(completed, dispatch_time(DISPATCH_TIME_NOW, 20 * NSEC_PER_SEC)) != 0)
                return Reply(@{@"error": @"TIMEOUT", @"message": @"Calendar authorization timed out; retry after responding to the system prompt"});
            status = [EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent];
        }
        // Authorized and FullAccess share value 3 on supported macOS versions.
        if (status != EKAuthorizationStatusAuthorized) return Denied(@"Allow full Calendar access in System Settings, then retry");
        if (checkOnly || [operation isEqualToString:@"authorize"]) return Reply(@{@"events": @[]});
        if ([operation isEqualToString:@"listCalendars"]) {
            NSMutableArray *calendars = [NSMutableArray array];
            for (EKCalendar *calendar in [store calendarsForEntityType:EKEntityTypeEvent]) {
                [calendars addObject:@{@"id": calendar.calendarIdentifier, @"title": calendar.title ?: @"",
                    @"writable": @(calendar.allowsContentModifications)}];
            }
            return Reply(@{@"calendars": calendars});
        }
        if ([operation isEqualToString:@"create"]) {
            EKCalendar *calendar = [store calendarWithIdentifier:args[@"calendarId"]];
            if (!calendar || !calendar.allowsContentModifications)
                return Reply(@{@"error": @"RESOURCE_NOT_FOUND", @"message": @"Writable calendar no longer available"});
            EKEvent *event = [EKEvent eventWithEventStore:store];
            NSString *operationUrl = args[@"operationUrl"];
            if (![operationUrl hasPrefix:@"openai-companion://calendar/operations/"])
                return Reply(@{@"error": @"INVALID_ARGUMENTS", @"message": @"Missing operation marker"});
            event.URL = [NSURL URLWithString:operationUrl];
            event.calendar = calendar;
            event.title = args[@"title"];
            event.startDate = [NSDate dateWithTimeIntervalSince1970:[args[@"start"] doubleValue] / 1000.0];
            event.endDate = [NSDate dateWithTimeIntervalSince1970:[args[@"end"] doubleValue] / 1000.0];
            event.timeZone = [NSTimeZone timeZoneWithName:args[@"timeZone"]];
            if (!event.timeZone) return Reply(@{@"error": @"INVALID_ARGUMENTS", @"message": @"Unsupported calendar timezone"});
            event.allDay = [args[@"allDay"] boolValue];
            event.location = args[@"location"];
            event.notes = args[@"notes"];
            NSError *error = nil;
            if (![store saveEvent:event span:EKSpanThisEvent commit:YES error:&error])
                return Reply(@{@"error": @"SERVER_INTERNAL_ERROR", @"message": @"Calendar save failed; check calendar before retrying"});
            if (!event.eventIdentifier) return Reply(@{@"error": @"SERVER_INTERNAL_ERROR", @"message": @"Saved event has no identifier"});
            return Reply(@{@"eventId": event.eventIdentifier});
        }
        NSDate *start = [NSDate dateWithTimeIntervalSince1970:[args[@"start"] doubleValue] / 1000.0];
        NSDate *end = [NSDate dateWithTimeIntervalSince1970:[args[@"end"] doubleValue] / 1000.0];
        if ([operation isEqualToString:@"recover"]) {
            EKCalendar *calendar = [store calendarWithIdentifier:args[@"calendarId"]];
            if (!calendar) return Reply(@{@"eventId": [NSNull null]});
            NSMutableArray *matches = [NSMutableArray array];
            for (EKEvent *event in [store eventsMatchingPredicate:[store predicateForEventsWithStartDate:start endDate:end calendars:@[calendar]]]) {
                if (event.status != EKEventStatusCanceled && [event.URL.absoluteString isEqualToString:args[@"operationUrl"]])
                    [matches addObject:event];
            }
            if (matches.count > 1) return Reply(@{@"error": @"SERVER_INTERNAL_ERROR", @"message": @"Ambiguous operation marker"});
            if ([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent] != EKAuthorizationStatusAuthorized)
                return Denied(@"Calendar access was revoked");
            EKEvent *found = matches.firstObject;
            return Reply(@{@"eventId": found.eventIdentifier ?: [NSNull null]});
        }
        NSArray<EKEvent *> *events = [store eventsMatchingPredicate:[store predicateForEventsWithStartDate:start endDate:end calendars:nil]];
        events = [events sortedArrayUsingComparator:^NSComparisonResult(EKEvent *a, EKEvent *b) {
            NSComparisonResult result = [a.startDate compare:b.startDate];
            if (result == NSOrderedSame) result = [a.endDate compare:b.endDate];
            if (result == NSOrderedSame) result = [(a.eventIdentifier ?: @"") compare:(b.eventIdentifier ?: @"")];
            return result;
        }];
        NSMutableArray *result = [NSMutableArray array];
        NSString *query = args[@"query"];
        for (EKEvent *event in events) {
            if (event.status == EKEventStatusCanceled || [event.endDate compare:start] != NSOrderedDescending || [event.startDate compare:end] != NSOrderedAscending) continue;
            if (query.length && [(event.title ?: @"") rangeOfString:query options:NSCaseInsensitiveSearch].location == NSNotFound) continue;
            NSMutableDictionary *item = [@{@"id": event.eventIdentifier ?: @"", @"title": event.title ?: @"",
                @"startTimeMs": @((long long)(event.startDate.timeIntervalSince1970 * 1000)),
                @"endTimeMs": @((long long)(event.endDate.timeIntervalSince1970 * 1000)),
                @"allDay": @(event.allDay), @"calendarName": event.calendar.title ?: [NSNull null]} mutableCopy];
            if ([args[@"includeLocation"] boolValue]) item[@"location"] = event.location ?: [NSNull null];
            if ([args[@"includeNotes"] boolValue]) item[@"notes"] = event.notes ?: [NSNull null];
            [result addObject:item];
            if (result.count >= [args[@"limit"] unsignedIntegerValue]) break;
        }
        if ([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent] != EKAuthorizationStatusAuthorized)
            return Denied(@"Calendar access was revoked");
        return Reply(@{@"events": result});
    }
}
void companion_calendar_free(void *value) { free(value); }
