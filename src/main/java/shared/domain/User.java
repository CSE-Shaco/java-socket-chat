package shared.domain;

import java.io.Serializable;

/**
 * Represents a user in the chat system.
 * Contains both internal userId and public-facing username.
 */
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String userId;
    private final String username;

    public User(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                '}';
    }
}
