// Package pocketxray runs Xray-core inside the app process. Outbound sockets are
// passed to Android VpnService.protect() through Protector.
package pocketxray

import (
	"bytes"
	"errors"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

	// Same feature set as the stock xray binary.
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Protector is implemented in Java; Protect must return true when the socket is excluded from the VPN.
type Protector interface {
	Protect(fd int32) bool
}

var (
	mu        sync.Mutex // guards server
	pmu       sync.RWMutex // guards protector; separate because Start holds mu while Xray calls control()
	protector Protector
	server    *core.Instance
	hooked    sync.Once
)

func control(network, address string, c syscall.RawConn) error {
	pmu.RLock()
	p := protector
	pmu.RUnlock()
	if p == nil {
		return errors.New("pocketxray: no protector")
	}
	var ok bool
	if err := c.Control(func(fd uintptr) { ok = p.Protect(int32(fd)) }); err != nil {
		return err
	}
	if !ok {
		return errors.New("pocketxray: protect failed")
	}
	return nil
}

// SetProtector must be called before Start.
func SetProtector(p Protector) {
	pmu.Lock()
	protector = p
	pmu.Unlock()
}

// Start launches Xray with a JSON config (stock Xray format). Any previous instance is stopped first.
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	pmu.RLock()
	noProt := protector == nil
	pmu.RUnlock()
	if noProt {
		return errors.New("pocketxray: SetProtector first")
	}
	if server != nil {
		_ = server.Close()
		server = nil
	}
	var hookErr error
	hooked.Do(func() {
		// Registered once: Xray keeps the controllers for the process lifetime.
		if hookErr = internet.RegisterDialerController(control); hookErr == nil {
			hookErr = internet.RegisterListenerController(control)
		}
	})
	if hookErr != nil {
		return hookErr
	}
	cfg, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	if err != nil {
		return err
	}
	s, err := core.New(cfg)
	if err != nil {
		return err
	}
	if err := s.Start(); err != nil {
		_ = s.Close()
		return err
	}
	server = s
	return nil
}

// Stop shuts the instance down; it is safe to call when nothing runs.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if server == nil {
		return nil
	}
	err := server.Close()
	server = nil
	return err
}

func Version() string { return core.Version() }

