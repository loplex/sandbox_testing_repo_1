/*
 * TransmitFile from mswsock, as a JDK 22 or later calls it for FileChannel.transferTo between two files: with the
 * target file's fd, -1, for a socket. "bad" calls it three times so; "socket" sends a file over a connected TCP
 * socket on localhost; "bad+socket" does the first and then the second, in one process.
 * Each call prints what it returned and WSAGetLastError() after it.
 */
#include <winsock2.h>
#include <mswsock.h>
#include <stdio.h>
#include <string.h>

static HANDLE open_file(void)
{
    HANDLE file = CreateFileA("tf-source.bin", GENERIC_READ | GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, NULL);
    char data[4096];
    DWORD written;
    memset(data, 'x', sizeof(data));
    WriteFile(file, data, sizeof(data), &written, NULL);
    SetFilePointer(file, 0, NULL, FILE_BEGIN);
    return file;
}

static void bad(HANDLE file)
{
    for (int i = 0; i < 3; i++) {
        WSASetLastError(0);
        BOOL ok = TransmitFile((SOCKET)-1, file, 4096, 0, NULL, NULL, TF_USE_KERNEL_APC);
        printf("bad socket, call %d: %s, WSAGetLastError %d\n", i + 1, ok ? "TRUE" : "FALSE", WSAGetLastError());
    }
}

static void real(HANDLE file)
{
    SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    int length = sizeof(address);
    bind(listener, (struct sockaddr *)&address, sizeof(address));
    listen(listener, 1);
    getsockname(listener, (struct sockaddr *)&address, &length);
    SOCKET client = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    connect(client, (struct sockaddr *)&address, sizeof(address));
    SOCKET server = accept(listener, NULL, NULL);
    SetFilePointer(file, 0, NULL, FILE_BEGIN);
    WSASetLastError(0);
    BOOL ok = TransmitFile(client, file, 4096, 0, NULL, NULL, 0);
    int error = WSAGetLastError();
    char buffer[8192];
    int received = ok ? recv(server, buffer, sizeof(buffer), 0) : 0;
    printf("real socket: %s, WSAGetLastError %d, %d bytes received\n", ok ? "TRUE" : "FALSE", error, received);
    closesocket(server);
    closesocket(client);
    closesocket(listener);
}

int main(int argc, char **argv)
{
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    HANDLE file = open_file();
    const char *mode = argc > 1 ? argv[1] : "bad";
    if (strstr(mode, "bad")) bad(file);
    if (strstr(mode, "socket")) real(file);
    CloseHandle(file);
    WSACleanup();
    return 0;
}
