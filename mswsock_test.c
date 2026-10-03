#define STANDALONE
#include <stdarg.h>
#include <string.h>
#include <winsock2.h>
#include <mswsock.h>
#include "wine/test.h"

static void tcp_socketpair_flags(SOCKET *src, SOCKET *dst, DWORD flags)
{
    SOCKET server = INVALID_SOCKET;
    struct sockaddr_in addr;
    int len, ret;

    *src = WSASocketW(AF_INET, SOCK_STREAM, IPPROTO_TCP, NULL, 0, flags);
    ok(*src != INVALID_SOCKET, "failed to create socket, error %u\n", WSAGetLastError());

    server = WSASocketW(AF_INET, SOCK_STREAM, IPPROTO_TCP, NULL, 0, flags);
    ok(server != INVALID_SOCKET, "failed to create socket, error %u\n", WSAGetLastError());

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    ret = bind(server, (struct sockaddr *)&addr, sizeof(addr));
    ok(!ret, "failed to bind socket, error %u\n", WSAGetLastError());

    len = sizeof(addr);
    ret = getsockname(server, (struct sockaddr *)&addr, &len);
    ok(!ret, "failed to get address, error %u\n", WSAGetLastError());

    ret = listen(server, 1);
    ok(!ret, "failed to listen, error %u\n", WSAGetLastError());

    ret = connect(*src, (struct sockaddr *)&addr, sizeof(addr));
    ok(!ret, "failed to connect, error %u\n", WSAGetLastError());

    len = sizeof(addr);
    *dst = accept(server, (struct sockaddr *)&addr, &len);
    ok(*dst != INVALID_SOCKET, "failed to accept socket, error %u\n", WSAGetLastError());

    closesocket(server);
}

static void tcp_socketpair(SOCKET *src, SOCKET *dst)
{
    tcp_socketpair_flags(src, dst, WSA_FLAG_OVERLAPPED);
}

/* mswsock's TransmitFile() looks the ws2_32 implementation up through the socket
 * it is given; an invalid one first must not break it for later calls. Nothing
 * else here calls it, so these are its first calls in the process. */
static void test_mswsock_TransmitFile(void)
{
    char path[MAX_PATH], data[64], buf[sizeof(data)];
    SOCKET src, dst;
    HANDLE file;
    DWORD size;
    BOOL bret;
    int i, ret;

    GetTempPathA(sizeof(path), path);
    strcat(path, "mswsock_TransmitFile.tmp");
    file = CreateFileA(path, GENERIC_READ | GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                       FILE_FLAG_DELETE_ON_CLOSE, NULL);
    ok(file != INVALID_HANDLE_VALUE, "failed to create file, error %lu\n", GetLastError());
    memset(data, 'x', sizeof(data));
    bret = WriteFile(file, data, sizeof(data), &size, NULL);
    ok(bret && size == sizeof(data), "failed to write file, error %lu\n", GetLastError());

    for (i = 0; i < 2; i++)
    {
        SetFilePointer(file, 0, NULL, FILE_BEGIN);
        WSASetLastError(0xdeadbeef);
        bret = TransmitFile(INVALID_SOCKET, file, 0, 0, NULL, NULL, 0);
        ok(!bret, "call %d: TransmitFile succeeded unexpectedly\n", i);
        ok(WSAGetLastError() == WSAENOTSOCK, "call %d: got error %u\n", i, WSAGetLastError());
    }

    tcp_socketpair(&src, &dst);
    SetFilePointer(file, 0, NULL, FILE_BEGIN);
    bret = TransmitFile(src, file, 0, 0, NULL, NULL, 0);
    ok(bret, "TransmitFile failed, error %u\n", WSAGetLastError());
    if (bret)
    {
        ret = recv(dst, buf, sizeof(buf), 0);
        ok(ret == sizeof(data), "got %d bytes\n", ret);
        ok(!memcmp(buf, data, sizeof(data)), "got other data\n");
    }

    closesocket(src);
    closesocket(dst);
    CloseHandle(file);
}

START_TEST(mswsock)
{
    WSADATA data;
    WSAStartup(MAKEWORD(2, 2), &data);
    test_mswsock_TransmitFile();
    WSACleanup();
}
