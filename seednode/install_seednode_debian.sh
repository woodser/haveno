#!/bin/sh
set -e

echo "[*] Bisq Seednode installation script"

##### change paths if necessary for your system

ROOT_USER=root
ROOT_GROUP=root
ROOT_PKG="build-essential libtool autotools-dev automake pkg-config bsdmainutils python3 git vim screen ufw"
ROOT_HOME=/root

SYSTEMD_SERVICE_HOME=/etc/systemd/system
SYSTEMD_ENV_HOME=/etc/default

BISQ_REPO_URL=https://github.com/bisq-network/bisq
BISQ_REPO_NAME=bisq
BISQ_REPO_TAG=v1.2.4
BISQ_HOME=/bisq
BISQ_USER=bisq

BITCOIN_REPO_URL=https://github.com/bitcoin/bitcoin
BITCOIN_REPO_NAME=bitcoin
BITCOIN_REPO_TAG=v0.19.0.1
BITCOIN_HOME=/bitcoin
BITCOIN_USER=bitcoin
BITCOIN_GROUP=bitcoin
BITCOIN_PKG="libevent-dev libboost-system-dev libboost-filesystem-dev libboost-chrono-dev libboost-test-dev libboost-thread-dev libdb-dev"

TOR_PKG="tor"
TOR_USER=debian-tor
TOR_GROUP=debian-tor
TOR_HOME=/etc/tor

#####

echo "[*] Updating apt repo sources"
sudo -H -i -u "${ROOT_USER}" apt-get update -q

echo "[*] Upgrading OS packages"
sudo -H -i -u "${ROOT_USER}" apt-get upgrade -q -y

echo "[*] Installing base packages"
sudo -H -i -u "${ROOT_USER}" apt-get install -q -y ${ROOT_PKG}

echo "[*] Cloning Bisq repo"
sudo -H -i -u "${ROOT_USER}" git config --global advice.detachedHead false
sudo -H -i -u "${ROOT_USER}" git clone --branch "${BISQ_REPO_TAG}" "${BISQ_REPO_URL}" "${ROOT_HOME}/${BISQ_REPO_NAME}"

echo "[*] Installing Tor"
sudo -H -i -u "${ROOT_USER}" apt-get install -q -y ${TOR_PKG}

echo "[*] Installing Tor configuration"
sudo -H -i -u "${ROOT_USER}" install -c -m 644 "${ROOT_HOME}/${BISQ_REPO_NAME}/seednode/torrc" "${TOR_HOME}/torrc"

echo "[*] Creating Bitcoin user with Tor access"
sudo -H -i -u "${ROOT_USER}" useradd -d "${BITCOIN_HOME}" -G "${TOR_GROUP}" "${BITCOIN_USER}"

echo "[*] Installing Bitcoin build dependencies"
sudo -H -i -u "${ROOT_USER}" apt-get install -q -y ${BITCOIN_PKG}

echo "[*] Creating Bitcoin homedir"
sudo -H -i -u "${ROOT_USER}" mkdir "${BITCOIN_HOME}"
sudo -H -i -u "${ROOT_USER}" chown "${BITCOIN_USER}":"${BITCOIN_GROUP}" ${BITCOIN_HOME}
sudo -H -i -u "${BITCOIN_USER}" ln -s . .bitcoin

echo "[*] Cloning Bitcoin repo"
sudo -H -i -u "${BITCOIN_USER}" git config --global advice.detachedHead false
sudo -H -i -u "${BITCOIN_USER}" git clone --branch "${BITCOIN_REPO_TAG}" "${BITCOIN_REPO_URL}" "${BITCOIN_HOME}/${BITCOIN_REPO_NAME}"

echo "[*] Building Bitcoin from source"
sudo -H -i -u "${BITCOIN_USER}" sh -c "cd ${BITCOIN_REPO_NAME} && ./autogen.sh --quiet && ./configure --quiet --disable-wallet --with-incompatible-bdb && make -j9"

echo "[*] Installing Bitcoin into OS"
sudo -H -i -u "${ROOT_USER}" sh -c "cd ${BITCOIN_HOME}/${BITCOIN_REPO_NAME} && make install >/dev/null"

echo "[*] Installing Bitcoin configuration"
sudo -H -i -u "${ROOT_USER}" install -c -o "${BITCOIN_USER}" -g "${BITCOIN_GROUP}" -m 644 "${ROOT_HOME}/${BISQ_REPO_NAME}/seednode/bitcoin.conf" "${BITCOIN_HOME}/bitcoin.conf"
sudo -H -i -u "${ROOT_USER}" install -c -o "${BITCOIN_USER}" -g "${BITCOIN_GROUP}" -m 755 "${ROOT_HOME}/${BISQ_REPO_NAME}/seednode/blocknotify.sh" "${BITCOIN_HOME}/blocknotify.sh"

echo "[*] Generating Bitcoin RPC credentials"
BITCOIN_RPC_USER=$(head -150 /dev/urandom | md5sum | awk '{print $1}')
sudo sed -i -e "s/__BITCOIN_RPC_USER__/${BITCOIN_RPC_USER}/" "${BITCOIN_HOME}/bitcoin.conf"
BITCOIN_RPC_PASS=$(head -150 /dev/urandom | md5sum | awk '{print $1}')
sudo sed -i -e "s/__BITCOIN_RPC_PASS__/${BITCOIN_RPC_PASS}/" "${BITCOIN_HOME}/bitcoin.conf"

echo "[*] Installing Bitcoin init scripts"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${ROOT_HOME}/${BISQ_REPO_NAME}/seednode/bitcoin.service" "${SYSTEMD_SERVICE_HOME}"

echo "[*] Creating Bisq user with Tor access"
sudo -H -i -u "${ROOT_USER}" useradd -d "${BISQ_HOME}" -G "${TOR_GROUP}" "${BISQ_USER}"

echo "[*] Creating Bisq homedir"
sudo -H -i -u "${ROOT_USER}" mkdir "${BISQ_HOME}"
sudo -H -i -u "${ROOT_USER}" chown "${BISQ_USER}":"${BISQ_GROUP}" ${BISQ_HOME}

echo "[*] Moving Bisq repo"
sudo -H -i -u "${ROOT_USER}" mv "${ROOT_HOME}/${BISQ_REPO_NAME}" "${BISQ_HOME}/${BISQ_REPO_NAME}"
sudo -H -i -u "${ROOT_USER}" chown -R "${BISQ_USER}:${BISQ_GROUP}" "${BISQ_HOME}/${BISQ_REPO_NAME}"

echo "[*] Installing OpenJDK 10.0.2 from Bisq repo"
sudo -H -i -u "${ROOT_USER}" "${BISQ_HOME}/${BISQ_REPO_NAME}/scripts/install_java.sh"

echo "[*] Building Bisq from source"
sudo -H -i -u "${BISQ_USER}" sh -c "cd ${BISQ_HOME}/${BISQ_REPO_NAME} && ./gradlew build -x test < /dev/null" # redirect from /dev/null is necessary to workaround gradlew non-interactive shell hanging issue

echo "[*] Installing Bisq init script"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${BISQ_HOME}/${BISQ_REPO_NAME}/seednode/bisq-seednode.service" "${SYSTEMD_SERVICE_HOME}/bisq-seednode.service"
sudo sed -i -e "s/__BISQ_REPO_NAME__/${BISQ_REPO_NAME}/" "${SYSTEMD_SERVICE_HOME}/bisq-seednode.service"
sudo sed -i -e "s/__BISQ_HOME__/${BISQ_HOME}/" "${SYSTEMD_SERVICE_HOME}/bisq-seednode.service"

echo "[*] Installing Bisq environment file with Bitcoin RPC credentials"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${BISQ_HOME}/${BISQ_REPO_NAME}/seednode/bisq-seednode.env" "${SYSTEMD_ENV_HOME}/bisq-seednode.env"
sudo sed -i -e "s/__BITCOIN_RPC_USER__/${BITCOIN_RPC_USER}/" "${SYSTEMD_ENV_HOME}/bisq-seednode.env"
sudo sed -i -e "s/__BITCOIN_RPC_PASS__/${BITCOIN_RPC_PASS}/" "${SYSTEMD_ENV_HOME}/bisq-seednode.env"
sudo sed -i -e "s/__BISQ_HOME__/${BISQ_HOME}/" "${SYSTEMD_ENV_HOME}/bisq-seednode.env"

echo "[*] Updating systemd daemon configuration"
sudo -H -i -u "${ROOT_USER}" systemctl daemon-reload
sudo -H -i -u "${ROOT_USER}" systemctl enable tor.service
sudo -H -i -u "${ROOT_USER}" systemctl enable bisq-seednode.service
sudo -H -i -u "${ROOT_USER}" systemctl enable bitcoin.service

echo "[*] Preparing firewall"
sudo -H -i -u "${ROOT_USER}" ufw default deny incoming
sudo -H -i -u "${ROOT_USER}" ufw default allow outgoing

echo "[*] Starting Tor"
sudo -H -i -u "${ROOT_USER}" systemctl start tor
echo "[*] Starting Bitcoin"
sudo -H -i -u "${ROOT_USER}" systemctl start bitcoin
sudo -H -i -u "${ROOT_USER}" journalctl --no-pager --unit bitcoin
sudo -H -i -u "${ROOT_USER}" tail "${BITCOIN_HOME}/debug.log"
echo "[*] Done!"
