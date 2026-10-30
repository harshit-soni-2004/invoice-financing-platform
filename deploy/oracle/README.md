# Always-on free hosting on Oracle Cloud

Oracle Cloud's Always Free tier includes Arm (Ampere A1) VMs with up to 4 OCPUs and 24 GB of memory in total, which is enough for the whole stack. `setup.sh` installs Docker, pulls the published multi-arch images from GHCR, and serves the app over HTTPS through Caddy. Caddy gets the certificates from Let's Encrypt and uses `sslip.io` host names, so no domain is needed:

| URL | Serves |
|---|---|
| `https://app.<ip-with-dashes>.sslip.io` | Angular app |
| `https://api.<ip-with-dashes>.sslip.io` | API gateway |
| `https://auth.<ip-with-dashes>.sslip.io` | Sign-in (OAuth 2.1 / OIDC) |

Only Caddy listens on ports 80 and 443. MySQL, RabbitMQ, Redis and the services stay on the internal Docker network, and MailHog is bound to `127.0.0.1` on the VM.

## Costs

Always Free resources are not billed. Sign-up asks for a card to verify identity, and Oracle may place a small temporary authorization on it. To stay at zero cost:

- Do not upgrade the account to Pay As You Go. Most Always Free accounts cannot then create paid resources, but an upgraded account can.
- Create only the resources below. Look for the "Always Free-eligible" label in the console.
- Oracle can reclaim Always Free compute instances that stay idle (CPU, network and memory all very low) for 7 days. This stack usually stays above those thresholds; if the VM is stopped, start it again from the console.

## 1. Create the account

1. Sign up at [oracle.com/cloud/free](https://www.oracle.com/cloud/free/).
2. Choose a home region that has Ampere A1 capacity. Your home region cannot be changed later, and Always Free compute only runs there.

## 2. Create the VM

In the console, go to **Compute → Instances → Create instance**:

1. **Image**: Canonical Ubuntu 24.04 (or 22.04).
2. **Shape**: Ampere, `VM.Standard.A1.Flex`, with 2 OCPUs and 12 GB of memory (up to 4 and 24 within Always Free).
3. **Networking**: create a new VCN with a public subnet, and assign a public IPv4 address.
4. **SSH keys**: upload your public key, or download the generated private key and keep it safe.
5. **Boot volume**: the default 50 GB is enough (Always Free covers 200 GB in total).

If you see "Out of capacity for shape VM.Standard.A1.Flex", try another availability domain, or try again later.

## 3. Open ports 80 and 443

Go to the instance's subnet, open its **security list**, and add **ingress rules** with source `0.0.0.0/0`:

| Protocol | Destination port | Purpose |
|---|---|---|
| TCP | 80 | Let's Encrypt validation, redirect to HTTPS |
| TCP | 443 | HTTPS |
| UDP | 443 | HTTP/3 (optional) |

`setup.sh` opens the same ports in the VM's own firewall (iptables), which Oracle's Ubuntu images also enforce.

## 4. Install

```bash
ssh ubuntu@<public-ip>
curl -fsSL https://raw.githubusercontent.com/harshit-soni-2004/invoice-financing-platform/main/deploy/oracle/setup.sh | bash
```

The first run takes about 5 to 10 minutes. The script prints the three URLs when the app and the sign-in server answer over HTTPS.

What it does:

1. Installs Docker, git and openssl. On Arm, it also installs the amd64 emulator for MailHog.
2. Opens ports 80 and 443 in iptables and saves the rules.
3. Clones the repository to `~/invoice-financing-platform`, or fast-forwards it if it is already there.
4. Creates `~/invoice-financing-platform/.env.public` once, with new random passwords for MySQL, RabbitMQ, Redis and the bootstrap admin. The file is readable only by you.
5. Writes the public URLs into that file, pulls the images, and starts Compose project `ifp` from `docker-compose.yml`, `deploy/compose.public.yml` and `deploy/oracle/compose.caddy.yml`.

Optional variables, passed to bash, e.g. `curl -fsSL <url> | IMAGE_TAG=sha-<commit> bash`:

| Variable | Default | Purpose |
|---|---|---|
| `DOMAIN` | `<ip-with-dashes>.sslip.io` | Your own domain. Point `app.`, `api.` and `auth.` at the VM first |
| `IMAGE_TAG` | `latest` | Pin an image version, e.g. `sha-<commit>` |
| `REPO_URL` | this repository | A fork. The images come from `ghcr.io/<fork owner>/ifp` |
| `BRANCH` | `main` | Branch to check out |

## Use it

- Sign in as `admin@ifp-demo.test`. The password is `ADMIN_BOOTSTRAP_PASSWORD` in `~/invoice-financing-platform/.env.public`.
- Registration emails (verification links) go to MailHog. To read them, run `ssh -L 8025:127.0.0.1:8025 ubuntu@<public-ip>` and open <http://localhost:8025>.

## Operate

```bash
cd ~/invoice-financing-platform
alias ifp='sudo docker compose -p ifp --env-file .env.public -f docker-compose.yml -f deploy/compose.public.yml -f deploy/oracle/compose.caddy.yml --profile app'
ifp ps                       # status
ifp logs -f --tail 100 caddy # or gateway, auth-service, ...
ifp stop                     # stop, keeping the data
ifp up -d --no-build --wait  # start again
```

To update to the newest images from `main`, run `setup.sh` again. It keeps `.env.public`, the data and the certificates.

`ifp down -v` deletes all the data, including the JWT signing key and the certificates. Let's Encrypt limits how many certificates a host name can get per week, so avoid deleting the certificates repeatedly.

## Troubleshooting

- **The URLs time out**: check the security list rules (step 3) and `sudo iptables -L INPUT -n --line-numbers`. The ACCEPT rules for 80 and 443 must come before the REJECT rule.
- **Certificate errors**: run `ifp logs caddy`. Let's Encrypt must reach port 80 from the internet.
- **A service is unhealthy after the first start**: run `ifp up -d --no-build --wait` again. On a busy VM, RabbitMQ can be slow on its first start.
- **Sign-in fails with a redirect error**: the URLs in `.env.public` must match the browser address. If the public IP changed, run `setup.sh` again.
