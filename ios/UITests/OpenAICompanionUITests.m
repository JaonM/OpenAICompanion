#import <XCTest/XCTest.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>

@interface OpenAICompanionUITests : XCTestCase
@end

@implementation OpenAICompanionUITests

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

    XCUIElement *message = app.textViews.firstMatch;
    XCTAssertTrue([message waitForExistenceWithTimeout:10]);
    [message tap];
    [message typeText:@"Reply with hi."];
    [app.buttons[@"发送"] tap];

    XCUIElement *reply = [[app.staticTexts matchingPredicate:
        [NSPredicate predicateWithFormat:@"label BEGINSWITH %@", @"assistant："]] firstMatch];
    XCTAssertTrue([reply waitForExistenceWithTimeout:120]);
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
