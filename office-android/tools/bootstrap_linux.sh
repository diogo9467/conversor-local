#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Ubuntu 24.04 in a dedicated build environment. Run this setup as root.
set -euo pipefail
test "$(id -u)" = 0
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl git build-essential \
    autoconf automake libtool pkg-config bison flex gperf python3 python3-dev \
    python3-lxml python3-polib zip unzip xz-utils ccache nasm cmake ninja-build \
    patch gettext libxml2-utils xsltproc libxml2-dev libxslt1-dev zlib1g-dev \
    libpng-dev libkrb5-dev libfontconfig1-dev libfreetype6-dev libx11-dev \
    libxext-dev libxrender-dev libxrandr-dev libxt-dev libxinerama-dev libxkbfile-dev \
    libxkbcommon-dev libxkbcommon-x11-dev libssl-dev libcap-dev libpam0g-dev \
    libcurl4-openssl-dev libpoco-dev libzstd-dev libcppunit-dev \
    openjdk-21-jdk-headless ant fonts-dejavu-core locales wget file rsync
id officebuild >/dev/null 2>&1 || useradd --create-home --shell /bin/bash officebuild
mkdir -p /opt/office-local/logs /opt/office-local/downloads
cd /opt/office-local
if [ ! -d upstream ]; then
    git clone --depth 1 --single-branch --branch cp-26.04.4-2 \
        https://github.com/CollaboraOnline/online.mirror.git upstream
fi
test "$(git -c safe.directory=/opt/office-local/upstream -C upstream rev-parse HEAD)" = 201368fc8ded1a0f28d1891c77e0410cb9416531
chown -R officebuild:officebuild /opt/office-local
echo 'UPSTREAM_READY — continue as officebuild'
