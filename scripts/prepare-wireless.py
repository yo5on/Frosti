"""Create the offline hotspot identity once. Uses cryptography from the setup Python."""
import datetime
import ipaddress
import json
import secrets
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

DATA = Path(__file__).resolve().parents[1] / "data"


def certificate(host):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "FROSTI laptop")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(host))]), critical=False)
            .sign(key, hashes.SHA256()))
    return key, cert


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--python", required=True, help="Core interpreter, with its dependencies installed")
    args = parser.parse_args()
    DATA.mkdir(exist_ok=True)
    settings_path = DATA / "wireless.json"
    if settings_path.exists():
        raise SystemExit("Wireless identity already exists; keeping existing pairing.")
    # shortcut: Windows' observed hotspot address is fixed here; re-pair if its subnet changes.
    host = "192.168.137.1"
    key, cert = certificate(host)
    (DATA / "wireless.key").write_bytes(key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    (DATA / "wireless.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (DATA / "wireless.cer").write_bytes(cert.public_bytes(serialization.Encoding.DER))
    settings_path.write_text(json.dumps(dict(ssid="FROSTI", password=secrets.token_urlsafe(18),
                                            host=host, python=str(Path(args.python).resolve())), indent=2))
    print("Wireless identity created. Hotspot credentials are in data/wireless.json.")
