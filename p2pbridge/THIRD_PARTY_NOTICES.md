# Third-party notices

The P2P transport depends on [github.com/blackjid/dahua-p2p](https://github.com/blackjid/dahua-p2p), pinned at commit `345f724f2b95f549f4725195c7d516e7b508c6aa`.

That project is distributed under the MIT License. Copyright (c) 2022 Alexey Khit and Copyright (c) 2026 Juan Ignacio Donoso. The upstream license text is available in the dependency's `LICENSE` file.

This integration is experimental. It uses the device serial number and the user's own device credentials to request a Dahua P2P tunnel. It does not bypass authentication. P2P availability depends on the recorder model, firmware, account configuration, network and Dahua cloud service.
