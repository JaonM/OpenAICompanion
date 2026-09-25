import SwiftUI

@main
struct OpenAICompanionApp: App {
    var body: some Scene {
        WindowGroup {
            SessionListView()
                .tint(.indigo)
        }
    }
}
