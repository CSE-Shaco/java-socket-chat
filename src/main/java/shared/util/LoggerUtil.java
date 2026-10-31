package shared.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Utility class for logging messages to the console.
 * Uses java.util.logging framework with a custom formatter and level control.
 */
public class LoggerUtil {

    // Shared logger instance for the chat application
    private static final Logger logger = Logger.getLogger("ChatLogger");

    static {
        // Prevent the default parent handlers from logging to console
        logger.setUseParentHandlers(false);

        // Create and configure a console handler
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter()); // Use default simple formatter
        handler.setLevel(Level.ALL); // Log all levels to the console

        // Attach handler to logger and set logger level
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
    }

    /**
     * Logs a normal info-level message.
     *
     * @param message the message to log
     */
    public static void log(String message) {
        logger.info(message);
    }

    /**
     * Logs an error-level message along with a throwable (exception).
     *
     * @param message   the error message to log
     * @param throwable the exception or error to log
     */
    public static void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }
}
