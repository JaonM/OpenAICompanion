use crate::Message;

/// One user interaction and the messages produced during that interaction.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Turn {
    pub messages: Vec<Message>,
}

impl Turn {
    pub fn new(user_input: impl Into<String>) -> Self {
        Self {
            messages: vec![Message::User {
                content: user_input.into(),
            }],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_user_turn() {
        let turn = Turn::new("hello");
        assert_eq!(
            turn.messages,
            vec![Message::User {
                content: "hello".into()
            }]
        );
    }
}
