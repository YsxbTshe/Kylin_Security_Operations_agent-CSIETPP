---
title: firewalld Concepts (upstream)
publisher: The firewalld Project (firewalld.org)
source_url: https://firewalld.org/documentation/concepts.html
retrieved: 2026-06-14
language: en
license: Upstream open-source project documentation. firewalld is the default dynamic firewall management tool used as-is in 银河麒麟/openKylin (see Kylin sysadmin manual §8.1.1). GPLv2 project.
upstream: true
topic: 防火墙 firewalld / zones / services / rich rules / runtime vs permanent
note: UPSTREAM documentation. firewalld is shipped and used unchanged in Kylin server/desktop; firewall-cmd and firewall-config are the Kylin-recommended firewall tools.
---

# firewalld Concepts (UPSTREAM — used as-is in Kylin)

> Upstream source: firewalld.org. In 银河麒麟高级服务器操作系统 V10 the firewall section (§8.1.1) documents firewalld, firewall-cmd, zones, services, ports, direct interface and rich language exactly as upstream. This page is the authoritative concept reference.

## Introduction

Firewalld operates as a stateful, zone-based firewall that uses policies and zones to organize rules. The network is logically divided into zones, with traffic between them governed by policies. These policies can filter input, forwarded, and output traffic, as well as perform network address translations (NAT).

## Zone Principles

Firewalld adheres to five core principles:

1. **Single Ingress Zone**: Traffic enters through exactly one zone
2. **Single Egress Zone**: Traffic exits through exactly one zone
3. **Trust Levels**: Each zone defines a specific trust level
4. **Intra-Zone Default**: intra-zone (within the same zone) is allowed by default
5. **Inter-Zone Default**: inter-zone (zone to zone) is denied by default

The last two principles follow logically from the third. Both are configurable — principle 4 via the `--remove-forward` zone option, and principle 5 by adding new policies.

## Firewall Rules and Policies

Policies apply firewall rules in a stateful, unidirectional manner. You only need to consider one traffic direction; the return path is implicitly allowed through stateful filtering.

Policies are associated with ingress zones (traffic origin) and egress zones (traffic destination). For example, a policy filtering traffic from an internal zone to an external zone can either allow all traffic via `--set-target ACCEPT` or restrict to specific services like HTTP and HTTPS.

## Zone Firewall Rules

Zones implicitly provide an input policy for traffic destined to the host itself. This simplifies configurations for end-stations providing services, such as web servers, without requiring explicit policies. Services can be added directly to zones using commands like `--add-service`.

## Runtime vs. Permanent Configuration

Firewalld maintains separate runtime and permanent configurations:

- **Permanent Configuration**: Persists across reloads and restarts
- **Runtime Configuration**: Lost after firewalld reload or restart; populated from permanent settings at startup

Changes use the `--permanent` flag for permanent modifications and omit it for runtime-only changes. Using `--reload` synchronizes both configurations while maintaining existing connections without interruption.

## Services

Services can be added to policies or zones to allow specific application traffic, such as HTTP, HTTPS, or SSH.

## Summary

Firewalld provides a simplified firewall model emphasizing zone-based organization, stateful filtering, and the separation of temporary runtime changes from persistent configuration.
