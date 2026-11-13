package pocketxray

import (
	"net"
	"testing"
	"time"
)

type fake struct{ n int }

func (f *fake) Protect(fd int32) bool { f.n++; return true }

// Starts Xray with a loopback SOCKS inbound and checks that its outbound dial reaches the protector.
func TestStartProtectsOutbound(t *testing.T) {
	srv, _ := net.Listen("tcp", "127.0.0.1:0")
	defer srv.Close()
	go func() { c, err := srv.Accept(); if err == nil { c.Close() } }()
	p := &fake{}
	SetProtector(p)
	cfg := `{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":19099,"protocol":"socks","settings":{"udp":false}}],"outbounds":[{"protocol":"freedom"}]}`
	if err := Start(cfg); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	c, err := net.Dial("tcp", "127.0.0.1:19099")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	c.Write([]byte{5, 1, 0})
	b := make([]byte, 2)
	c.Read(b)
	host := srv.Addr().(*net.TCPAddr)
	req := append([]byte{5, 1, 0, 1, 127, 0, 0, 1}, byte(host.Port>>8), byte(host.Port))
	c.Write(req)
	c.Read(make([]byte, 10))
	if p.n == 0 {
		t.Fatalf("protector not called")
	}
	t.Log("version", Version(), "protect calls", p.n)
}
