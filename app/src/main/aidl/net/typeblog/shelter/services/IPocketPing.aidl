package net.typeblog.shelter.services;

/** Private server check in its own process; request and result are JSON, see vpn/PingService. */
interface IPocketPing {
    String test(String request);
}
