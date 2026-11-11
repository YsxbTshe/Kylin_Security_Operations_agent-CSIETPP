package main

import (
	"os/user"
	"strconv"
	"testing"
)

func TestResolvePeerUIDAcceptsNumericUID(t *testing.T) {
	uid, err := resolvePeerUID(" 1001 ")
	if err != nil {
		t.Fatal(err)
	}
	if uid == nil || *uid != 1001 {
		t.Fatalf("期望 uid=1001，实际 %v", uid)
	}
}

func TestResolvePeerUIDAnyDisablesCheck(t *testing.T) {
	uid, err := resolvePeerUID("ANY")
	if err != nil {
		t.Fatal(err)
	}
	if uid != nil {
		t.Fatalf("any 应关闭校验，实际返回 uid=%d", *uid)
	}
}

func TestResolvePeerUIDRejectsUnknownUser(t *testing.T) {
	// 未知用户必须报错让进程退出，而不是悄悄放行。
	if _, err := resolvePeerUID("kylin-no-such-user-6f3a"); err == nil {
		t.Fatal("期望未知用户解析失败")
	}
}

func TestResolvePeerUIDRejectsEmpty(t *testing.T) {
	if _, err := resolvePeerUID("  "); err == nil {
		t.Fatal("期望空值解析失败")
	}
}

func TestResolvePeerUIDLooksUpCurrentUser(t *testing.T) {
	self, err := user.Current()
	if err != nil {
		t.Skipf("当前环境无法查询用户：%v", err)
	}
	uid, err := resolvePeerUID(self.Username)
	if err != nil {
		t.Fatal(err)
	}
	expected, err := strconv.ParseUint(self.Uid, 10, 32)
	if err != nil {
		t.Skipf("当前用户 uid 非数字（%s），跳过", self.Uid)
	}
	if uid == nil || *uid != uint32(expected) {
		t.Fatalf("期望 uid=%d，实际 %v", expected, uid)
	}
}
