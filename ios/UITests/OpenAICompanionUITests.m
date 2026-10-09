#import <XCTest/XCTest.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>

@interface OpenAICompanionUITests : XCTestCase
@end

@implementation OpenAICompanionUITests

- (void)setUp {
    [super setUp];
    self.continueAfterFailure = NO;
}

- (XCUIApplication *)acceptanceApp {
    XCUIApplication *app = [[XCUIApplication alloc] init];
    app.launchEnvironment = @{@"COMPANION_ACCEPTANCE_SYSTEM_KEYBOARD": @"1", @"COMPANION_ACCEPTANCE_MODEL_DIAGNOSTICS": @"1"};
    return app;
}

- (void)openSettingsSection:(NSString *)section app:(XCUIApplication *)app {
    if ([section isEqualToString:@"远端 Agent（A2A）"]) {
        [self openSettingsSection:@"跨设备执行" app:app];
        XCUIElement *advanced = app.buttons[@"高级设置（令牌与第三方智能体）"];
        for (NSInteger i = 0; i < 6 && !advanced.hittable; i++) [app swipeUp];
        [advanced tap];
        [app.buttons[@"远端 Agent（A2A）"] tap];
        return;
    }
    [app.buttons[@"设置"] tap];
    XCUIElement *entry = [[app descendantsMatchingType:XCUIElementTypeAny]
        matchingIdentifier:[@"settings-" stringByAppendingString:section]].firstMatch;
    for (NSInteger i = 0; i < 6 && !entry.hittable; i++) [app swipeUp];
    XCTAssertTrue(entry.hittable);
    [entry tap];
    XCTAssertTrue([app.buttons[@"‹ 所有设置"] waitForExistenceWithTimeout:5]);
}

- (void)retainScreenshot:(XCUIApplication *)app name:(NSString *)name {
    XCTAttachment *attachment = [XCTAttachment attachmentWithScreenshot:app.screenshot];
    attachment.name = name;
    attachment.lifetime = XCTAttachmentLifetimeKeepAlways;
    [self addAttachment:attachment];
}

- (void)enterAcceptanceText:(NSString *)text field:(XCUIElement *)field app:(XCUIApplication *)app {
    for (NSInteger i = 0; i < 5 && !field.hittable; i++) [app swipeUp];
    XCTAssertTrue(field.hittable);
    // Target the editable part instead of the floating label. Scroll settling
    // can otherwise leave the first tap without keyboard focus on real devices.
    [[field coordinateWithNormalizedOffset:CGVectorMake(0.25, 0.7)] tap];
    if (![app.keyboards.firstMatch waitForExistenceWithTimeout:2]) [field tap];
    XCTAssertTrue([app.keyboards.firstMatch waitForExistenceWithTimeout:5]);
    [field typeText:[@"" stringByPaddingToLength:100 withString:XCUIKeyboardKeyDelete startingAtIndex:0]];
    [field typeText:text];
}

// Opt-in real-device acceptance. Copy a protected, disposable fixture to the
// test runner's Documents directory; never embed credentials in launch arguments.
- (void)testCrossDevicePairingAndForegroundExecution {
    NSString *path = [NSHomeDirectory() stringByAppendingPathComponent:@"Documents/device-acceptance.json"];
    NSData *data = [NSData dataWithContentsOfFile:path];
    if (!data) XCTSkip(@"需要为独立真机验收准备设备配对 fixture。");
    NSDictionary *fixture = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    XCTAssertTrue([fixture[@"endpoint"] hasPrefix:@"https://"]);
    XCTAssertTrue([fixture[@"code"] length] > 0);
    XCUIApplication *app = [self acceptanceApp];
    app.launchArguments = @[@"-localGGUFFileName", fixture[@"modelFileName"] ?: @"test-smollm2.gguf"];
    [app launch];
    [self openSettingsSection:@"跨设备执行" app:app];
    XCUIElement *connected = [[app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label CONTAINS %@ AND label CONTAINS %@", @"Acceptance iPhone", @"前台可接单"]] firstMatch];
    XCUIElement *endpoint = app.textViews[@"设备服务地址"];
    for (NSInteger i = 0; i < 12 && !endpoint.hittable; i++) [app swipeUp];
    if (![connected waitForExistenceWithTimeout:20]) {
        XCTAssertTrue(endpoint.hittable, @"设备服务地址输入框不可操作：%@", app.debugDescription);
        [self enterAcceptanceText:fixture[@"endpoint"] field:endpoint app:app];
        XCUIElement *name = app.textViews[@"本设备名称"];
        [self enterAcceptanceText:@"Acceptance iPhone" field:name app:app];
        // Password fields can have a different accessibility element type.
        XCUIElement *code = [[app descendantsMatchingType:XCUIElementTypeAny]
            matchingIdentifier:@"一次性配对码"].firstMatch;
        [self enterAcceptanceText:fixture[@"code"] field:code app:app];
        XCUIElement *accept = app.buttons[@"前台接单开关"];
        for (NSInteger i = 0; i < 5 && !accept.hittable; i++) [app swipeUp];
        [accept tap];
        XCTAssertTrue(app.buttons[@"使用配对码连接"].enabled, @"配对码输入未生效，尚未发起网络配对请求。");
        [app.buttons[@"使用配对码连接"] tap];
        XCTAssertTrue([connected waitForExistenceWithTimeout:40]);
    }
    [app terminate];
    [app launch];
    [self openSettingsSection:@"跨设备执行" app:app];
    for (NSInteger i = 0; i < 12 && !connected.hittable; i++) [app swipeUp];
    XCTAssertTrue([connected waitForExistenceWithTimeout:40]);
    // The host-side acceptance controller sends a task while the real app is
    // foreground. Its result is checked independently at the HTTPS gateway.
    XCTestExpectation *foreground = [self expectationWithDescription:@"Foreground device task window"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 120 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{ [foreground fulfill]; });
    [self waitForExpectations:@[foreground] timeout:130];
    // Completing a real task temporarily displays its execution/result status.
    XCTAssertTrue([connected waitForExistenceWithTimeout:40]);
}

- (NSUInteger)latestRemoteTaskNumber:(XCUIApplication *)app {
    NSUInteger latest = 0;
    XCUIElementQuery *titles = [app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"远端任务编号 "]];
    for (XCUIElement *title in titles.allElementsBoundByIndex) {
        NSScanner *scanner = [NSScanner scannerWithString:[title.label substringFromIndex:[@"远端任务编号 " length]]];
        NSInteger number = 0;
        if ([scanner scanInteger:&number] && number > 0) latest = MAX(latest, (NSUInteger)number);
    }
    return latest;
}

- (void)testToolMessagesCollapseAndExpand {
    NSData *data = [NSData dataWithContentsOfFile:[NSHomeDirectory() stringByAppendingPathComponent:@"Documents/device-acceptance.json"]];
    if (!data) XCTSkip(@"需要包含真实工具轨迹的独立验收 fixture。");
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    XCUIElement *collapsed = [app.buttons matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"› 工具："]].firstMatch;
    for (NSInteger i = 0; i < 20 && !collapsed.hittable; i++) [app swipeDown];
    XCTAssertTrue(collapsed.hittable, @"没有找到默认折叠的真实工具记录。");
    XCUIElement *body = [app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"tool："]].firstMatch;
    XCTAssertFalse(body.exists);
    [self retainScreenshot:app name:@"Collapsed tool history"];
    [collapsed tap];
    XCTAssertTrue([body waitForExistenceWithTimeout:5]);
    [self retainScreenshot:app name:@"Expanded tool history"];
    [app.buttons[@"⌄ 工具"] tap];
    XCTAssertFalse(body.exists);
}

- (void)testSettingsNavigationAndDraftPreservation {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    XCTAssertFalse(app.buttons[@"刷新"].exists, @"对话页不应常驻远端任务刷新栏。");
    NSString *draft = @"Draft retained across navigation";
    [self enterAcceptanceText:draft field:app.textViews[@"输入消息"] app:app];
    XCTAssertTrue(app.buttons[@"send-message"].enabled, @"草稿输入未写入。");
    [self retainScreenshot:app name:@"Draft before navigation"];
    [app.buttons[@"任务"] tap];
    XCTAssertTrue(app.buttons[@"刷新"].exists);
    XCTAssertFalse(app.buttons[@"send-message"].exists);
    [app.buttons[@"对话"] tap];
    XCTAssertFalse(app.buttons[@"刷新"].exists);
    [self retainScreenshot:app name:@"Draft after navigation"];
    XCTAssertTrue(app.buttons[@"send-message"].enabled, @"导航后草稿应仍可发送。");
    [self openSettingsSection:@"跨设备执行" app:app];
    XCTAssertTrue(app.textViews[@"设备服务地址"].exists);
    [app.buttons[@"‹ 所有设置"] tap];
    XCUIElement *model = [[app descendantsMatchingType:XCUIElementTypeAny] matchingIdentifier:@"settings-端侧模型"].firstMatch;
    [model tap];
    XCTAssertTrue(app.buttons[@"导入 GGUF"].exists);
    [self retainScreenshot:app name:@"Grouped model settings"];
    [app.buttons[@"‹ 所有设置"] tap];
    [self retainScreenshot:app name:@"Settings overview"];
    [app.buttons[@"完成"] tap];
    [self retainScreenshot:app name:@"Draft after navigation"];
    XCTAssertTrue(app.buttons[@"send-message"].enabled, @"导航后草稿应仍可发送。");
    // Compose TextView exposes no AX value on iOS. Verify the actual submitted
    // text instead of inferring draft preservation from that empty property.
    [app.buttons[@"send-message"] tap];
    XCUIElement *submitted = [[app descendantsMatchingType:XCUIElementTypeAny] matchingPredicate:
        [NSPredicate predicateWithFormat:@"identifier BEGINSWITH %@ AND label == %@",
            @"conversation-message-", [@"user：" stringByAppendingString:draft]]].firstMatch;
    XCTAssertTrue([submitted waitForExistenceWithTimeout:20]);
    XCTAssertTrue([app.buttons[@"send-message"] waitForExistenceWithTimeout:120]);
}

// Requires the real phone to be paired and the acceptance Mac worker online.
- (void)testCrossDeviceDelegatesToMacAndShowsResult {
    [self verifyCrossDeviceWithEngine:nil];
}

- (void)testMlxCrossDeviceDelegatesToMacAndShowsResult {
    [self verifyCrossDeviceWithEngine:@"MLX"];
}

- (void)verifyCrossDeviceWithEngine:(NSString *)engine {
    NSData *data = [NSData dataWithContentsOfFile:[NSHomeDirectory() stringByAppendingPathComponent:@"Documents/device-acceptance.json"]];
    if (!data) XCTSkip(@"需要独立真机跨设备验收 fixture。");
    NSDictionary *fixture = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    XCUIApplication *app = [self acceptanceApp];
    app.launchArguments = @[@"-localGGUFFileName", fixture[@"modelFileName"] ?: @"test-smollm2.gguf"];
    [app launch];
    if (engine) {
        [self openSettingsSection:@"端侧模型" app:app];
        [app.buttons[engine] tap];
        XCTNSPredicateExpectation *ready = [[XCTNSPredicateExpectation alloc]
            initWithPredicate:[NSPredicate predicateWithFormat:@"enabled == YES"] object:app.buttons[engine]];
        XCTAssertEqual([XCTWaiter waitForExpectations:@[ready] timeout:180], XCTWaiterResultCompleted);
        [app.buttons[@"完成"] tap];
    }
    [self openSettingsSection:@"远端 Agent（A2A）" app:app];
    XCUIElement *enable = app.buttons[@"启用 Agent Acceptance Mac"];
    XCUIElement *disable = app.buttons[@"停用 Agent Acceptance Mac"];
    for (NSInteger i = 0; i < 15 && !enable.hittable && !disable.hittable; i++) [app swipeUp];
    XCTAssertTrue(enable.hittable || disable.hittable, @"尚未发现验收 Mac Agent。");
    if (enable.hittable) [enable tap];
    XCTAssertTrue([disable waitForExistenceWithTimeout:20]);
    [app.buttons[@"完成"] tap];
    if (app.buttons[@"任务"].exists) [app.buttons[@"任务"] tap];
    NSUInteger priorTask = [self latestRemoteTaskNumber:app];
    if (app.buttons[@"对话"].exists) [app.buttons[@"对话"] tap];
    XCUIElement *message = app.textViews.firstMatch;
    XCTAssertTrue([message waitForExistenceWithTimeout:10]);
    [self enterAcceptanceText:fixture[@"routingPrompt"] ?: @"请把任务「计算17+25，只输出数字，不要解释，不要调用工具」委托给 Acceptance Mac。不要在手机本地计算。" field:message app:app];
    XCTAssertTrue(app.buttons[@"send-message"].enabled);
    [app.buttons[@"send-message"] tap];
    XCUIElement *thinking = [[app descendantsMatchingType:XCUIElementTypeAny] matchingIdentifier:@"assistant-thinking"].firstMatch;
    XCTAssertTrue([thinking waitForExistenceWithTimeout:10]);
    NSPredicate *routed = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
        return app.state == XCUIApplicationStateNotRunning || app.buttons[@"发送一次"].exists || app.buttons[@"send-message"].exists;
    }];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[[[XCTNSPredicateExpectation alloc] initWithPredicate:routed object:app]] timeout:300], XCTWaiterResultCompleted);
    XCTAssertTrue(app.buttons[@"发送一次"].exists, @"未产生远端委托确认，不能把口头答复计作执行成功。");
    XCTAssertTrue(app.staticTexts[@"Acceptance Mac"].exists);
    [app.buttons[@"发送一次"] tap];
    XCTAssertTrue([app.buttons[@"任务"] waitForExistenceWithTimeout:20]);
    // Stay in the conversation: the originating delegation must receive its live result here.
    __block NSUInteger createdTask = 0;
    NSPredicate *newTask = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
        createdTask = [self latestRemoteTaskNumber:app];
        return createdTask > priorTask;
    }];
    XCTNSPredicateExpectation *created = [[XCTNSPredicateExpectation alloc] initWithPredicate:newTask object:app];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[created] timeout:20], XCTWaiterResultCompleted);
    NSString *resultPattern = [NSString stringWithFormat:@"任务 %lu 远端结果： *(42|17 *\\+ *25 *= *42) *", (unsigned long)createdTask];
    NSPredicate *newResult = [NSPredicate predicateWithFormat:@"label MATCHES %@", resultPattern];
    XCTAssertTrue([[app.staticTexts matchingPredicate:newResult].firstMatch waitForExistenceWithTimeout:180]);
    XCTAssertTrue(app.textViews.firstMatch.exists, @"远端结果必须返回对话前台。");
    [self retainScreenshot:app name:@"Companion remote result in conversation"];
}

// Verify the card for a host-verified real task; this does not submit a new task.
- (void)testCompletedCrossDeviceTaskIsVisible {
    NSData *data = [NSData dataWithContentsOfFile:[NSHomeDirectory() stringByAppendingPathComponent:@"Documents/device-acceptance.json"]];
    NSDictionary *fixture = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    if (![fixture[@"completedTaskText"] length] || ![fixture[@"completedTaskResult"] length])
        XCTSkip(@"需要先完成并由服务端核验真实远端任务。");
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    XCTAssertTrue([app.buttons[@"任务"] waitForExistenceWithTimeout:20]);
    [app.buttons[@"任务"] tap];
    NSNumber *number = fixture[@"completedLocalTaskId"] ?: @1;
    NSString *title = [NSString stringWithFormat:@"远端任务编号 %@ · Acceptance Mac", number];
    for (NSInteger i = 0; i < 10 && !app.staticTexts[title].exists; i++) [app swipeUp];
    XCTAssertTrue([app.staticTexts[title] waitForExistenceWithTimeout:10]);
    XCTAssertTrue(app.staticTexts[fixture[@"completedTaskText"]].exists);
    XCTAssertTrue(app.staticTexts[@"已完成"].exists);
    NSString *result = [NSString stringWithFormat:@"任务 %@ 远端结果：%@", number, fixture[@"completedTaskResult"]];
    XCTAssertTrue(app.staticTexts[result].exists);
}

- (void)testScheduledReminderSurvivesAppTermination {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    [self openSettingsSection:@"主动任务" app:app];
    if (![app.staticTexts[@"Acceptance scheduled notification"] waitForExistenceWithTimeout:5]) {
        XCTSkip(@"需要在独立模拟器准备一次性提醒测试数据。");
    }
    XCUIElement *toggle = app.buttons[@"主动推送开关"];
    XCTAssertTrue([toggle waitForExistenceWithTimeout:10]);
    [toggle tap];
    XCUIApplication *springboard = [[XCUIApplication alloc] initWithBundleIdentifier:@"com.apple.springboard"];
    XCUIElement *allow = springboard.alerts.buttons[@"允许"];
    if ([allow waitForExistenceWithTimeout:5]) [allow tap];
    XCTAssertTrue([app.staticTexts[@"系统已安排 1 个计划提醒"] waitForExistenceWithTimeout:15]);
    [app terminate];
    XCUIElement *notification = springboard.staticTexts[@"Acceptance scheduled notification"];
    XCTAssertTrue([notification waitForExistenceWithTimeout:180]);
}

- (void)testSingleConversationAndSettingsSurviveRelaunch {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];

    XCTAssertTrue([app.buttons[@"send-message"] waitForExistenceWithTimeout:10]);
    XCTAssertFalse([app.buttons[@"新建会话"] exists]);

    [self openSettingsSection:@"端侧模型" app:app];
    XCTAssertTrue([app.buttons[@"导入 GGUF"] waitForExistenceWithTimeout:10]);
    [self retainScreenshot:app name:@"Companion settings"];
    [app.buttons[@"完成"] tap];

    [app terminate];
    [app launch];
    XCTAssertTrue([app.buttons[@"send-message"] waitForExistenceWithTimeout:20]);
    XCTAssertFalse([app.buttons[@"新建会话"] exists]);
}

- (XCUIElement *)sendKeyboardPrompt:(NSString *)prompt app:(XCUIApplication *)app {
    return [self sendKeyboardPrompt:prompt app:app verifyStreaming:NO];
}

- (XCUIElement *)sendKeyboardPrompt:(NSString *)prompt app:(XCUIApplication *)app verifyStreaming:(BOOL)verifyStreaming {
    XCUIElement *message = app.textViews.firstMatch;
    XCTAssertTrue([message waitForExistenceWithTimeout:10]);
    [self enterAcceptanceText:prompt field:message app:app];
    XCTAssertTrue(app.buttons[@"send-message"].enabled, @"自动化输入未生效，不能计作模型请求。");
    [message typeText:@"\n"];
    XCUIElement *thinking = [[app descendantsMatchingType:XCUIElementTypeAny]
        matchingIdentifier:@"assistant-thinking"].firstMatch;
    XCTAssertTrue([thinking waitForExistenceWithTimeout:10], @"回车应提交请求并显示思考气泡。");
    [self retainScreenshot:app name:@"Thinking bubble after keyboard send"];
    if (verifyStreaming) {
        XCUIElement *reasoning = [[app descendantsMatchingType:XCUIElementTypeAny] matchingIdentifier:@"reasoning-streaming"].firstMatch;
        XCTAssertTrue([reasoning waitForExistenceWithTimeout:180], @"思考内容应在正文出现前逐步显示。");
        NSString *initialReasoning = reasoning.label;
        NSPredicate *reasoningGrows = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
            return reasoning.exists && reasoning.label.length > initialReasoning.length;
        }];
        XCTAssertEqual([XCTWaiter waitForExpectations:@[[[XCTNSPredicateExpectation alloc] initWithPredicate:reasoningGrows object:app]] timeout:20], XCTWaiterResultCompleted);
        XCTAssertLessThanOrEqual(reasoning.label.length, 1210u);
        [self retainScreenshot:app name:@"Bounded live reasoning"];
        XCUIElement *stream = [[app descendantsMatchingType:XCUIElementTypeAny] matchingIdentifier:@"assistant-streaming"].firstMatch;
        XCTAssertTrue([stream waitForExistenceWithTimeout:300], @"应在推理完成前显示流式正文。");
        XCTAssertTrue(thinking.exists, @"不能把一次性最终回复计作流式输出。");
        NSString *firstText = stream.label;
        NSPredicate *grows = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
            return thinking.exists && stream.exists && stream.label.length > firstText.length;
        }];
        XCTNSPredicateExpectation *growth = [[XCTNSPredicateExpectation alloc] initWithPredicate:grows object:app];
        XCTAssertEqual([XCTWaiter waitForExpectations:@[growth] timeout:30], XCTWaiterResultCompleted,
            @"推理尚未完成时，正文必须继续增长。");
        XCTAssertFalse([stream.label containsString:@"<think>"]);
        XCTAssertFalse([stream.label containsString:@"{\"text\":"]);
        [self retainScreenshot:app name:@"Growing streamed answer before completion"];
    }
    // LazyColumn recycles older rows: visible reply counts cannot identify a new reply.
    XCUIElement *request = [[app descendantsMatchingType:XCUIElementTypeAny] matchingPredicate:
        [NSPredicate predicateWithFormat:@"identifier BEGINSWITH %@ AND label == %@", @"conversation-message-", [@"user：" stringByAppendingString:prompt]]].firstMatch;
    for (NSInteger i = 0; i < 10 && !request.exists; i++) [app swipeDown];
    XCTAssertTrue([request waitForExistenceWithTimeout:10]);
    NSScanner *scanner = [NSScanner scannerWithString:[request.identifier substringFromIndex:[@"conversation-message-" length]]];
    NSInteger index = -1;
    XCTAssertTrue([scanner scanInteger:&index] && index >= 0);
    // A thinking record can be inserted before the answer; identify a new assistant row by index.
    NSMutableArray *replyIds = [NSMutableArray array];
    // Reasoning and tool rows can precede the answer; never match an older turn.
    for (NSInteger offset = 1; offset <= 32; offset++) {
        [replyIds addObject:[NSString stringWithFormat:@"conversation-message-%ld-assistant", (long)(index + offset)]];
    }
    XCUIElement *reply = [[app descendantsMatchingType:XCUIElementTypeAny]
        matchingPredicate:[NSPredicate predicateWithFormat:@"identifier IN %@", replyIds]].firstMatch;
    XCUIElement *modelError = [app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"model error:"]].firstMatch;
    NSPredicate *finished = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
        return app.state == XCUIApplicationStateNotRunning || reply.exists || modelError.exists || app.buttons[@"发送一次"].exists;
    }];
    XCTNSPredicateExpectation *finishedReply = [[XCTNSPredicateExpectation alloc] initWithPredicate:finished object:app];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[finishedReply] timeout:300], XCTWaiterResultCompleted);
    XCTAssertNotEqual(app.state, XCUIApplicationStateNotRunning, @"验收 App 已退出，不能继续计作模型等待。");
    XCTAssertFalse(app.buttons[@"发送一次"].exists, @"普通问答不应发起远端委托确认。");
    XCTAssertTrue(reply.exists, @"新模型没有返回正文：%@", modelError.exists ? modelError.label : @"等待超时");
    XCTAssertTrue([reply.label hasPrefix:@"assistant："] && reply.label.length > [@"assistant：" length]);
    XCTAssertFalse([reply.label containsString:@"</think>"]);
    XCTNSPredicateExpectation *settled = [[XCTNSPredicateExpectation alloc]
        initWithPredicate:[NSPredicate predicateWithFormat:@"exists == NO"] object:thinking];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[settled] timeout:10], XCTWaiterResultCompleted,
        @"回复完成后应移除思考气泡。");
    XCTAssertFalse([reply.label hasPrefix:@"assistant：{\"text\":"]);
    XCTNSPredicateExpectation *visibleReply = [[XCTNSPredicateExpectation alloc]
        initWithPredicate:[NSPredicate predicateWithFormat:@"hittable == YES"] object:reply];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[visibleReply] timeout:10], XCTWaiterResultCompleted,
        @"新回复应自动进入可视区，不能只检查其存在。");
    [self retainScreenshot:app name:@"Companion conversation"];
    return reply;
}

- (void)testLocalModelGeneratesReply {
    NSData *data = [NSData dataWithContentsOfFile:[NSHomeDirectory() stringByAppendingPathComponent:@"Documents/device-acceptance.json"]];
    NSDictionary *fixture = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    XCUIApplication *app = [self acceptanceApp];
    app.launchArguments = @[@"-localGGUFFileName", fixture[@"modelFileName"] ?: @"test-smollm2.gguf"];
    [app launch];

    [self openSettingsSection:@"端侧模型" app:app];
    if (![[[app.staticTexts matchingPredicate:[NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"端侧模型已导入"]] firstMatch] waitForExistenceWithTimeout:5]) {
        XCTSkip(@"需要先把测试 GGUF 放入模拟器 App 的 Models 目录；运行 scripts/test-ios-model.sh。");
    }
    [app.buttons[@"完成"] tap];
    XCTAssertTrue([app.buttons[@"send-message"] waitForExistenceWithTimeout:10]);

    NSString *prompt = [@"Reply with hi. Acceptance request " stringByAppendingString:NSUUID.UUID.UUIDString];
    XCUIElement *reply = [self sendKeyboardPrompt:prompt app:app];
    if (fixture) {
        [app swipeDown];
        [app swipeDown];
        XCTAssertTrue(app.buttons[@"↓ 最新消息"].exists);
        [app.buttons[@"↓ 最新消息"] tap];
        XCTAssertTrue(reply.hittable, @"返回最新消息后应显示刚生成的答案。");
    }

}

- (void)testMlxEngineSelectionAndDialogue {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    XCTAssertTrue([app.buttons[@"MLX"] waitForExistenceWithTimeout:10]);
    [app.buttons[@"MLX"] tap];
    XCUIElement *available = [app.staticTexts matchingPredicate:[NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"MLX 模型可用"]].firstMatch;
    XCTAssertTrue([available waitForExistenceWithTimeout:20], @"需要先下载并校验指定 MLX 模型。");
    [self retainScreenshot:app name:@"MLX engine and downloaded Qwen3.5 model"];
    [app.buttons[@"完成"] tap];
    XCUIElement *reply = [self sendKeyboardPrompt:[@"17+25等于多少？只输出数字。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString] app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：42");
    reply = [self sendKeyboardPrompt:[@"上一条答案加8，只输出数字，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString] app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：50");
    [app terminate];
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    XCTAssertTrue([available waitForExistenceWithTimeout:10], @"普通重启必须保留 MLX 引擎选择。");
    [app.buttons[@"llama.cpp"] tap];
    XCUIElement *gguf = [app.staticTexts matchingPredicate:[NSPredicate predicateWithFormat:@"label BEGINSWITH %@ AND label CONTAINS %@", @"端侧模型已导入", @"Qwen3.5-4B-Q8_0"]].firstMatch;
    XCTAssertTrue([gguf waitForExistenceWithTimeout:20], @"切回 llama.cpp 必须保留原 GGUF 模型。");
    [app.buttons[@"完成"] tap];
    reply = [self sendKeyboardPrompt:[@"17+25等于多少？只输出数字，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString] app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：42");
}

- (void)testQwen35StreamsReplyAndCanStop {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    [app.buttons[@"llama.cpp"] tap];
    [app.buttons[@"完成"] tap];
    [self verifyStreamedReplyAndStop:app];
}

- (void)testMlxOrdinaryQuestionStaysLocal {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    [app.buttons[@"MLX"] tap];
    [app.buttons[@"完成"] tap];
    [app.buttons[@"任务"] tap];
    NSUInteger priorTask = [self latestRemoteTaskNumber:app];
    [app.buttons[@"对话"] tap];
    XCUIElement *reply = [self sendKeyboardPrompt:[@"17+25等于多少？只输出数字。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString] app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：42");
    [app.buttons[@"任务"] tap];
    XCTAssertEqual([self latestRemoteTaskNumber:app], priorTask, @"普通问答不能创建远端任务。");
    [app.buttons[@"对话"] tap];
}

- (void)testModelLibraryAndHealthQuestionStayLocal {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    [app.buttons[@"MLX"] tap];
    XCUIElement *available = [app.staticTexts matchingPredicate:[NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"MLX 模型可用"]].firstMatch;
    XCTAssertTrue([available waitForExistenceWithTimeout:180], @"必须等待引擎切换完成再输入问答。");
    XCTAssertTrue([app.staticTexts[@"模型库"] waitForExistenceWithTimeout:10]);
    XCUIElement *small = app.staticTexts[@"Qwen3 0.6B · GGUF Q8（轻量）"];
    for (NSInteger i = 0; i < 8 && !small.hittable; i++) [app swipeUp];
    XCTAssertTrue(small.hittable);
    [self retainScreenshot:app name:@"Device model library compatibility"];
    [app.buttons[@"完成"] tap];
    [app.buttons[@"任务"] tap];
    NSUInteger priorTask = [self latestRemoteTaskNumber:app];
    [app.buttons[@"对话"] tap];
    XCUIElement *reply = [self sendKeyboardPrompt:@"柿子能和螃蟹一起吃吗" app:app];
    XCTAssertFalse([reply.label containsString:@"已委托"]);
    XCTAssertFalse([reply.label containsString:@"已提交"]);
    [app.buttons[@"任务"] tap];
    XCTAssertEqual([self latestRemoteTaskNumber:app], priorTask);
    [app.buttons[@"对话"] tap];
}

- (void)testMlxStreamsReplyAndCanStop {
    XCUIApplication *app = [self acceptanceApp];
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    [app.buttons[@"MLX"] tap];
    [app.buttons[@"完成"] tap];
    [self verifyStreamedReplyAndStop:app];
    [self openSettingsSection:@"端侧模型" app:app];
    [app.buttons[@"llama.cpp"] tap];
    [app.buttons[@"完成"] tap];
}

- (void)verifyStreamedReplyAndStop:(XCUIApplication *)app {
    NSString *prompt = [@"新的独立问题，忽略以前委托。从1数到30，用英文逗号分隔。只输出这一串数字，不要解释，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString];
    XCUIElement *reply = [self sendKeyboardPrompt:prompt app:app verifyStreaming:YES];
    NSMutableArray *numbers = [NSMutableArray array];
    for (NSInteger n = 1; n <= 30; n++) [numbers addObject:[NSString stringWithFormat:@"%ld", (long)n]];
    XCTAssertEqualObjects(reply.label, [@"assistant：" stringByAppendingString:[numbers componentsJoinedByString:@","]],
        @"最终正文必须完整且没有重复的流片段。");
    XCUIElement *message = app.textViews.firstMatch;
    [self enterAcceptanceText:[@"从1数到1000，用英文逗号分隔，不要解释，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString] field:message app:app];
    [message typeText:@"\n"];
    XCUIElement *stream = [[app descendantsMatchingType:XCUIElementTypeAny] matchingIdentifier:@"assistant-streaming"].firstMatch;
    XCTAssertTrue([stream waitForExistenceWithTimeout:300]);
    XCTAssertTrue(app.buttons[@"停止"].exists);
    [app.buttons[@"停止"] tap];
    XCUIElement *thinking = [[app descendantsMatchingType:XCUIElementTypeAny] matchingIdentifier:@"assistant-thinking"].firstMatch;
    NSPredicate *settled = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
        return !thinking.exists && !stream.exists && app.buttons[@"send-message"].exists;
    }];
    XCTNSPredicateExpectation *stopped = [[XCTNSPredicateExpectation alloc] initWithPredicate:settled object:app];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[stopped] timeout:20], XCTWaiterResultCompleted);
    XCTAssertFalse(stream.exists);
    XCTAssertTrue(app.buttons[@"send-message"].exists);
    [self retainScreenshot:app name:@"Stopped streaming generation"];
}

- (void)testQwen35ReplacementAndDialogue {
    NSData *data = [NSData dataWithContentsOfFile:[NSHomeDirectory() stringByAppendingPathComponent:@"Documents/device-acceptance.json"]];
    NSDictionary *fixture = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    NSString *file = fixture[@"modelFileName"];
    if (![file isEqualToString:@"Qwen3.5-4B-Q8_0.gguf"]) XCTSkip(@"需要已校验并复制到 App 的 Qwen3.5-4B Q8_0。");
    NSLog(@"Acceptance physical memory bytes: %llu", NSProcessInfo.processInfo.physicalMemory);
    XCUIApplication *app = [self acceptanceApp];
    app.launchArguments = @[@"-localGGUFFileName", file];
    [app launch];
    NSString *first = [@"这是一条新的独立问题，忽略以前的委托。17+25等于多少？只输出数字，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString];
    XCUIElement *reply = [self sendKeyboardPrompt:first app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：42", @"新模型必须正确回答当前问题。");
    NSString *followup = [@"上一条答案加8，只输出数字，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString];
    reply = [self sendKeyboardPrompt:followup app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：50", @"新模型必须保留多轮上下文。");
    // Activate and remove the old model only after two real inference checks pass.
    [app terminate];
    app.launchEnvironment = @{@"COMPANION_ACCEPTANCE_SYSTEM_KEYBOARD": @"1", @"COMPANION_ACCEPTANCE_ACTIVATE_MODEL": @"1"};
    [app launch];
    reply = [self sendKeyboardPrompt:[@"17+25等于多少？只输出数字，不要调用工具。验收编号 " stringByAppendingString:NSUUID.UUID.UUIDString] app:app];
    XCTAssertEqualObjects(reply.label, @"assistant：42");
    [app terminate];
    app.launchArguments = @[];
    app.launchEnvironment = @{@"COMPANION_ACCEPTANCE_SYSTEM_KEYBOARD": @"1", @"COMPANION_ACCEPTANCE_MODEL_DIAGNOSTICS": @"1"};
    [app launch];
    [self openSettingsSection:@"端侧模型" app:app];
    XCUIElement *selected = [app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@ AND label CONTAINS %@", @"端侧模型已导入", @"Qwen3.5-4B-Q8_0"]].firstMatch;
    XCTAssertTrue([selected waitForExistenceWithTimeout:10], @"普通重启必须保留新模型选择。");
    [self retainScreenshot:app name:@"Qwen3.5 Q8 persistent model selection"];
    [app.buttons[@"完成"] tap];
}

- (void)testConnectLocalMcpFixture {
    int connection = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons(8765)};
    inet_pton(AF_INET, "127.0.0.1", &address.sin_addr);
    BOOL available = connection >= 0 &&
        connect(connection, (struct sockaddr *)&address, sizeof(address)) == 0;
    if (connection >= 0) close(connection);
    if (!available) XCTSkip(@"需要先运行 python3 scripts/mcp_smoke_server.py");

    XCUIApplication *app = [self acceptanceApp];
    app.launchArguments = @[@"-mcpEndpoint", @"http://127.0.0.1:8765/mcp"];
    [app launch];
    [self openSettingsSection:@"远程 MCP" app:app];

    XCUIElement *connected = [[app.staticTexts matchingPredicate:
        // Three fixture tools, plus routing tools only when a device gateway is configured.
        [NSPredicate predicateWithFormat:@"label IN %@", @[@"已连接 · 3 个工具", @"已连接 · 5 个工具"]]] firstMatch];
    XCTAssertTrue([connected waitForExistenceWithTimeout:20]);
}

- (void)testDeviceToolExtensionIsOptional {
    XCUIApplication *app = [self acceptanceApp];
    app.launchArguments = @[@"-mcpEndpoint", @"http://127.0.0.1:8765/mcp", @"-deviceToolsEnabled", @"NO"];
    [app launch];
    [self openSettingsSection:@"工具扩展" app:app];
    XCUIElement *connected = [app.staticTexts matchingPredicate:[NSPredicate predicateWithFormat:@"label IN %@", @[@"已连接 · 3 个工具", @"已连接 · 5 个工具"]]].firstMatch;
    XCTAssertTrue([connected waitForExistenceWithTimeout:20]);
    NSString *baseLabel = connected.label;
    NSString *extendedLabel = [baseLabel isEqual:@"已连接 · 5 个工具"] ? @"已连接 · 9 个工具" : @"已连接 · 7 个工具";
    XCUIElement *toggle = app.buttons[@"端侧工具扩展开关"];
    XCTAssertTrue([toggle waitForExistenceWithTimeout:10]);
    [toggle tap];
    XCTAssertTrue([app.staticTexts[extendedLabel] waitForExistenceWithTimeout:10]);
    [toggle tap];
    XCTAssertTrue([app.staticTexts[baseLabel] waitForExistenceWithTimeout:10]);
}

@end
