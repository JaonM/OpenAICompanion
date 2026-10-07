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

- (void)enterAcceptanceText:(NSString *)text field:(XCUIElement *)field app:(XCUIApplication *)app {
    for (NSInteger i = 0; i < 5 && !field.hittable; i++) [app swipeUp];
    XCTAssertTrue(field.hittable);
    // Target the editable part instead of the floating label. Scroll settling
    // can otherwise leave the first tap without keyboard focus on real devices.
    [[field coordinateWithNormalizedOffset:CGVectorMake(0.25, 0.7)] tap];
    if (![app.keyboards.firstMatch waitForExistenceWithTimeout:2]) [field tap];
    XCTAssertTrue([app.keyboards.firstMatch waitForExistenceWithTimeout:5]);
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
    XCUIApplication *app = [[XCUIApplication alloc] init];
    app.launchArguments = @[@"-localGGUFFileName", @"test-smollm2.gguf"];
    [app launch];
    [app.buttons[@"设置"] tap];
    XCUIElement *endpoint = app.textViews[@"设备服务地址"];
    for (NSInteger i = 0; i < 12 && !endpoint.hittable; i++) [app swipeUp];
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
    XCUIElement *connected = app.staticTexts[@"已连接 · Acceptance iPhone · 前台可接单"];
    XCTAssertTrue([connected waitForExistenceWithTimeout:40]);
    [app terminate];
    [app launch];
    [app.buttons[@"设置"] tap];
    for (NSInteger i = 0; i < 12 && !connected.hittable; i++) [app swipeUp];
    XCTAssertTrue([connected waitForExistenceWithTimeout:40]);
    // The host-side acceptance controller sends a task while the real app is
    // foreground. Its result is checked independently at the HTTPS gateway.
    XCTestExpectation *foreground = [self expectationWithDescription:@"Foreground device task window"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 120 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{ [foreground fulfill]; });
    [self waitForExpectations:@[foreground] timeout:130];
    XCTAssertTrue(connected.exists);
}

- (void)testScheduledReminderSurvivesAppTermination {
    XCUIApplication *app = [[XCUIApplication alloc] init];
    [app launch];
    [app.buttons[@"设置"] tap];
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
    XCUIApplication *app = [[XCUIApplication alloc] init];
    [app launch];

    XCTAssertTrue([app.buttons[@"发送"] waitForExistenceWithTimeout:10]);
    XCTAssertFalse([app.buttons[@"新建会话"] exists]);

    [app.buttons[@"设置"] tap];
    XCTAssertTrue([app.buttons[@"导入 GGUF"] waitForExistenceWithTimeout:10]);
    [app.buttons[@"完成"] tap];

    [app terminate];
    [app launch];
    XCTAssertTrue([app.buttons[@"发送"] waitForExistenceWithTimeout:20]);
    XCTAssertFalse([app.buttons[@"新建会话"] exists]);
}

- (void)testLocalModelGeneratesReply {
    XCUIApplication *app = [[XCUIApplication alloc] init];
    app.launchArguments = @[@"-localGGUFFileName", @"test-smollm2.gguf"];
    [app launch];

    [app.buttons[@"设置"] tap];
    if (![app.staticTexts[@"端侧模型已导入"] waitForExistenceWithTimeout:5]) {
        XCTSkip(@"需要先把测试 GGUF 放入模拟器 App 的 Models 目录；运行 scripts/test-ios-model.sh。");
    }
    [app.buttons[@"完成"] tap];
    XCTAssertTrue([app.buttons[@"发送"] waitForExistenceWithTimeout:10]);

    XCUIElementQuery *replies = [app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"assistant："]];
    NSUInteger priorReplies = replies.count;

    XCUIElement *message = app.textViews.firstMatch;
    XCTAssertTrue([message waitForExistenceWithTimeout:10]);
    [message tap];
    [message typeText:@"Reply with hi."];
    NSPredicate *enabled = [NSPredicate predicateWithFormat:@"enabled == YES"];
    XCTNSPredicateExpectation *ready = [[XCTNSPredicateExpectation alloc] initWithPredicate:enabled object:app.buttons[@"发送"]];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[ready] timeout:5], XCTWaiterResultCompleted,
                   @"自动化输入未生效；请切换至苹果系统键盘后重试，不能计作模型请求。");
    [app.buttons[@"发送"] tap];

    NSPredicate *newReply = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
        return replies.count > priorReplies;
    }];
    XCTNSPredicateExpectation *generated = [[XCTNSPredicateExpectation alloc] initWithPredicate:newReply object:app];
    XCTAssertEqual([XCTWaiter waitForExpectations:@[generated] timeout:120], XCTWaiterResultCompleted);
}

- (void)testConnectLocalMcpFixture {
    int connection = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons(8765)};
    inet_pton(AF_INET, "127.0.0.1", &address.sin_addr);
    BOOL available = connection >= 0 &&
        connect(connection, (struct sockaddr *)&address, sizeof(address)) == 0;
    if (connection >= 0) close(connection);
    if (!available) XCTSkip(@"需要先运行 python3 scripts/mcp_smoke_server.py");

    XCUIApplication *app = [[XCUIApplication alloc] init];
    app.launchArguments = @[@"-mcpEndpoint", @"http://127.0.0.1:8765/mcp"];
    [app launch];
    [app.buttons[@"设置"] tap];

    XCUIElement *connected = [[app.staticTexts matchingPredicate:
        // Three fixture tools plus two shared device-routing tools.
        [NSPredicate predicateWithFormat:@"label == %@", @"已连接 · 5 个工具"]] firstMatch];
    XCTAssertTrue([connected waitForExistenceWithTimeout:20]);
}

- (void)testDeviceToolExtensionIsOptional {
    XCUIApplication *app = [[XCUIApplication alloc] init];
    app.launchArguments = @[@"-mcpEndpoint", @"http://127.0.0.1:8765/mcp", @"-deviceToolsEnabled", @"NO"];
    [app launch];
    [app.buttons[@"设置"] tap];
    XCTAssertTrue([app.staticTexts[@"已连接 · 5 个工具"] waitForExistenceWithTimeout:20]);
    XCUIElement *toggle = app.buttons[@"端侧工具扩展开关"];
    XCTAssertTrue([toggle waitForExistenceWithTimeout:10]);
    [toggle tap];
    XCTAssertTrue([app.staticTexts[@"已连接 · 9 个工具"] waitForExistenceWithTimeout:10]);
    [toggle tap];
    XCTAssertTrue([app.staticTexts[@"已连接 · 5 个工具"] waitForExistenceWithTimeout:10]);
}

@end
