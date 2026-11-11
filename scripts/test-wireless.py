"""Offline certificate trust and hostname checks; no hardware or runtime files touched."""
import importlib.util
import ipaddress
import ssl
import tempfile
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import serialization

spec = importlib.util.spec_from_file_location("wireless", Path(__file__).with_name("prepare-wireless.py"))
wireless = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wireless)
key, cert = wireless.certificate("192.168.137.1")
assert cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.IPAddress) == [ipaddress.ip_address("192.168.137.1")]
assert key.public_key().public_numbers() == cert.public_key().public_numbers()
with tempfile.TemporaryDirectory() as directory:
    pem, private = Path(directory) / "cert.pem", Path(directory) / "key.pem"
    pem.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server.load_cert_chain(pem, private)
    def handshake(client, hostname):
        ci, co, si, so = (ssl.MemoryBIO() for _ in range(4))
        c, s = client.wrap_bio(ci, co, server_hostname=hostname), server.wrap_bio(si, so, server_side=True)
        done = [False, False]
        for _ in range(20):
            for i, endpoint in enumerate((c, s)):
                try:
                    endpoint.do_handshake(); done[i] = True
                except ssl.SSLWantReadError:
                    pass
            si.write(co.read()); ci.write(so.read())
            if all(done): return
        raise AssertionError("Handshake stalled")
    handshake(ssl.create_default_context(cafile=str(pem)), "192.168.137.1")
    for client, hostname in ((ssl.create_default_context(), "192.168.137.1"),
                             (ssl.create_default_context(cafile=str(pem)), "192.168.137.2")):
        try:
            handshake(client, hostname)
        except ssl.SSLCertVerificationError:
            pass
        else:
            raise AssertionError("Untrusted certificate or wrong host accepted")
print("TLS checks passed: paired identity accepted; wrong identity and address rejected.")
