import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_network import java_proxy_options, proxy_environment, sdkmanager_proxy_options, launch_gradle
from prepare_emulator import select_archive


class ProxyTests(unittest.TestCase):
    def test_http_proxy_is_explicit_https_fallback(self):
        env = proxy_environment({'HTTP_PROXY': 'http://127.0.0.1:1082'})
        self.assertEqual('http://127.0.0.1:1082', env['https_proxy'])
        options = java_proxy_options(env)
        self.assertIn('-Dhttps.proxyHost=127.0.0.1', options)
        self.assertIn('-Dhttps.proxyPort=1082', options)
        self.assertEqual(['--proxy=http', '--proxy_host=127.0.0.1', '--proxy_port=1082'], sdkmanager_proxy_options(env))

    def test_explicit_https_and_lowercase_preferences_are_preserved(self):
        env = proxy_environment({'HTTP_PROXY': 'http://first:1', 'http_proxy': 'http://second:2',
                                 'HTTPS_PROXY': 'http://third:3'})
        self.assertEqual('http://second:2', env['http_proxy'])
        self.assertEqual('http://third:3', env['https_proxy'])

    def test_all_proxy_socks_reaches_java_and_sdkmanager(self):
        env = {'ALL_PROXY': 'socks5h://127.0.0.1:1082'}
        self.assertIn('-DsocksProxyHost=127.0.0.1', java_proxy_options(env))
        self.assertIn('-DsocksProxyPort=1082', java_proxy_options(env))
        self.assertEqual(['--proxy=socks', '--proxy_host=127.0.0.1', '--proxy_port=1082'], sdkmanager_proxy_options(env))

    def test_java_non_proxy_hosts_include_localhost_and_domain_patterns(self):
        options = java_proxy_options({'HTTP_PROXY': 'http://proxy:1082', 'NO_PROXY': '.example.com,192.168.0.0/16'})
        bypass = next(item for item in options if item.startswith('-Dhttp.nonProxyHosts='))
        self.assertIn('localhost|127.*|[::1]', bypass)
        self.assertIn('example.com|*.example.com', bypass)
        self.assertNotIn('/16', bypass)

    def test_no_proxy_means_no_java_or_sdkmanager_flags(self):
        self.assertEqual([], java_proxy_options({}))
        self.assertEqual([], sdkmanager_proxy_options({}))

    def test_invalid_proxy_fails_without_echoing_configuration(self):
        for value in ['http://proxy:wrong', 'ftp://proxy:21', 'http://proxy\n:1082']:
            with self.assertRaises(ValueError) as failure:
                java_proxy_options({'HTTP_PROXY': value})
            self.assertNotIn(value, str(failure.exception))

    def test_gradle_launcher_passes_proxy_to_client_and_daemon_without_output(self):
        with patch.dict(os.environ, {'HTTP_PROXY': 'http://127.0.0.1:1082'}, clear=True), \
                patch('build_network.os.execve') as launch, patch('builtins.print') as output:
            launch_gradle('/tmp/gradle', [':kgm-core:test'])
        _, arguments, env = launch.call_args.args
        self.assertIn('-Dhttps.proxyPort=1082', arguments)
        self.assertIn('-Dhttps.proxyPort=1082', env['GRADLE_OPTS'])
        self.assertNotIn('JAVA_TOOL_OPTIONS', env)
        output.assert_not_called()


class EmulatorArchiveTests(unittest.TestCase):
    XML = b'''<repository xmlns="urn:test">
      <remotePackage path="emulator"><revision><major>99</major></revision><channelRef ref="channel-2"/>
        <archives><archive><host-os>macosx</host-os><host-arch>aarch64</host-arch>
          <complete><url>beta.zip</url><checksum type="sha1">beta</checksum></complete>
        </archive></archives></remotePackage>
      <remotePackage path="emulator"><revision><major>37</major><minor>2</minor><micro>12</micro></revision><channelRef ref="channel-0"/>
        <archives><archive><host-os>macosx</host-os><host-arch>x64</host-arch>
          <complete><url>x64.zip</url><checksum type="sha1">intel</checksum></complete>
        </archive><archive><host-os>macosx</host-os><host-arch>aarch64</host-arch>
          <complete><url>arm.zip</url><checksum type="sha1">arm</checksum></complete>
        </archive></archives></remotePackage></repository>'''

    def test_selects_stable_arm_archive_instead_of_x64_or_newer_beta(self):
        self.assertEqual(((37, 2, 12), 'arm.zip', 'arm', 'sha1'), select_archive(self.XML, 'macosx', 'aarch64'))
        self.assertEqual(((37, 2, 12), 'x64.zip', 'intel', 'sha1'), select_archive(self.XML, 'macosx', 'x64'))

    def test_missing_host_is_not_silently_replaced_by_another_cpu(self):
        with self.assertRaises(RuntimeError):
            select_archive(self.XML, 'linux', 'aarch64')


if __name__ == '__main__':
    unittest.main()
