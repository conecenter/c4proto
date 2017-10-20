
appender("CONSOLE", ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
    }
}

def appName = System.getenv("C4STATE_TOPIC_PREFIX")

appender("ROLLING", RollingFileAppender) {
    //file = "./db4/logback/xxx.log"
    encoder(PatternLayoutEncoder) {
        Pattern = "%d %level %thread %mdc %logger - %m%n"
    }
    rollingPolicy(TimeBasedRollingPolicy) {
        FileNamePattern = "./db4/logback/%d{yyyy-MM-dd}/${appName}.log"
        maxHistory = 7
        totalSizeCap = "1GB"
    }
}

root(INFO, ["CONSOLE", "ROLLING"]) //TRACE