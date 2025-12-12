
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/types.h>
#include <arpa/inet.h>

#define PORT 9090
#define LOCKER_GPIO 40
#define PULSE_DURATION_MS 600

static volatile int keep_running = 1;
void handle_signal(int sig) { (void)sig; keep_running = 0; }

static int write_sysfs(const char *path, const char *value) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    ssize_t w = write(fd, value, strlen(value));
    close(fd);
    return (w == (ssize_t)strlen(value)) ? 0 : -1;
}

static int export_gpio(int gpio) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", gpio);
    return write_sysfs("/sys/class/gpio/export", buf);
}

static int unexport_gpio(int gpio) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", gpio);
    return write_sysfs("/sys/class/gpio/unexport", buf);
}

int main(void) {
    char path_value[128];
    snprintf(path_value, sizeof(path_value), "/sys/class/gpio/gpio%d/value", LOCKER_GPIO);

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    // export gpio (ignore error if already exported)
    if (export_gpio(LOCKER_GPIO) < 0) {
        // keep going: maybe already exported
    }
    usleep(100000);

    char path_direction[128];
    snprintf(path_direction, sizeof(path_direction), "/sys/class/gpio/gpio%d/direction", LOCKER_GPIO);
    if (write_sysfs(path_direction, "out") < 0) {
        fprintf(stderr, "Failed to set direction for gpio%d\n", LOCKER_GPIO);
        // continue anyway
    }
    // safe initial state
    write_sysfs(path_value, "0");

    // create server (127.0.0.1 only)
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("socket");
        return 1;
    }
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(PORT);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind");
        close(server_fd);
        return 1;
    }
    if (listen(server_fd, 4) < 0) {
        perror("listen");
        close(server_fd);
        return 1;
    }

    printf("lockerd listening on 127.0.0.1:%d\n", PORT);

    while (keep_running) {
        int client = accept(server_fd, NULL, NULL);
        if (client < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            break;
        }

        char buf[128];
        ssize_t n = read(client, buf, sizeof(buf)-1);
        if (n > 0) {
            buf[n] = 0;
            // strip trailing whitespace/newline
            while (n>0 && (buf[n-1]=='\n' || buf[n-1]=='\r' || buf[n-1]==' ')) { buf[n-1]=0; n--; }

            if (strcasecmp(buf, "OPEN") == 0) {
                printf("Command OPEN received\n");
                if (write_sysfs(path_value, "1") == 0) {
                    usleep(PULSE_DURATION_MS * 1000);
                    write_sysfs(path_value, "0");
                    const char *resp = "OK LOCK OPENED\n";
                    write(client, resp, strlen(resp));
                } else {
                    const char *err = "ERR GPIO WRITE\n";
                    write(client, err, strlen(err));
                }
            } else if (strcasecmp(buf, "STATUS") == 0) {
                const char *s = "OK RUNNING\n";
                write(client, s, strlen(s));
            } else {
                const char *u = "UNKNOWN\n";
                write(client, u, strlen(u));
            }
        }
        close(client);
    }

    // cleanup
    write_sysfs(path_value, "0");
    unexport_gpio(LOCKER_GPIO);
    close(server_fd);
    printf("lockerd exit\n");
    return 0;
}
