(() => {
  const platforms = new Map([...document.querySelectorAll('[data-platform]')].map(a => [a.dataset.platform, a]));
  const recommended = document.getElementById('recommended-download');
  const status = document.getElementById('platform-status');
  const packs = document.querySelector('[data-pack-downloads]');
  const platformNames = {'windows-x64':'Windows','macos-arm64':'macOS (Apple Silicon)','macos-x64':'macOS (Intel)','linux-x64':'Linux','linux-arch-x64':'Arch Linux','linux-debian-x64':'Debian / Ubuntu'};
  let manifest = {};
  async function getMetadata(url) {
    const response = await fetch(url, {cache:'no-store', signal:AbortSignal.timeout(15000)});
    if (!response.ok) throw new Error('Download metadata unavailable');
    return response.json();
  }
  function setDownload(anchor, item, label) {
    if (!anchor || !item?.url) return;
    const url = new URL(item.url, location.origin);
    if (url.protocol !== 'https:') return;
    anchor.href = url.href;
    anchor.removeAttribute('aria-disabled');
    anchor.classList.remove('unavailable');
    if (label) anchor.textContent = label;
  }
  async function loadLauncherMetadata() {
    try {
      const release = await getMetadata('https://api.github.com/repos/TheStonedGamer/aeromon-launcher/releases/latest');
      const match = /^v(\d+\.\d+\.\d+)$/.exec(release.tag_name || '');
      if (!match || release.draft || release.prerelease) throw new Error('No published stable launcher release');
      const version = match[1];
      const assets = new Map((release.assets || []).map(asset => [asset.name, asset]));
      const definitions = [
        {platform:'windows-x64', filename:`Aeromon-${version}.msi`, label:'Intel / AMD - MSI - per-user'},
        {platform:'macos-arm64', filename:`Aeromon-${version}-macos-arm64.dmg`, label:'Apple Silicon - DMG'},
        {platform:'macos-x64', filename:`Aeromon-${version}-macos-x64.dmg`, label:'Intel - DMG'},
        {platform:'linux-x64', filename:`Aeromon-${version}-linux-x64.tar.gz`, label:'Intel / AMD - TAR.GZ - portable'},
        {platform:'linux-debian-x64', filename:`aeromon_${version}_amd64.deb`, label:'Intel / AMD - DEB'},
      ];
      const launchers = definitions.map(item => {
        const asset = assets.get(item.filename);
        if (!asset?.browser_download_url) throw new Error(`Missing release asset: ${item.filename}`);
        const url = new URL(asset.browser_download_url);
        if (url.protocol !== 'https:' || url.hostname !== 'github.com') throw new Error(`Unexpected release URL: ${item.filename}`);
        return {platform:item.platform, url:url.href, label:item.label, version, sha256:asset.digest?.replace(/^sha256:/, '')};
      });
      const arch = (release.assets || []).find(asset => asset.name.startsWith(`aeromon-launcher-bin-${version}-`) && /-\d+-x86_64\.pkg\.tar\.zst$/.test(asset.name));
      if (!arch?.browser_download_url) throw new Error('Missing Arch Linux package');
      const archUrl = new URL(arch.browser_download_url);
      if (archUrl.protocol !== 'https:' || archUrl.hostname !== 'github.com') throw new Error('Unexpected Arch Linux package URL');
      launchers.push({platform:'linux-arch-x64', url:archUrl.href, label:'Intel / AMD - pacman package', version, sha256:arch.digest?.replace(/^sha256:/, '')});
      return {launchers};
    } catch {
      return getMetadata('/manifest.json');
    }
  }
  const launcherLoaded = loadLauncherMetadata().then(data => {
    manifest = data;
    const archPackage = data.launchers?.find(item => item.platform === 'linux-arch-x64');
    const archCommand = document.getElementById('arch-install-command');
    if (archPackage?.url && archCommand) {
      const url = new URL(archPackage.url, location.origin);
      const filename = url.pathname.split('/').pop();
      if (url.protocol === 'https:' && /^aeromon-launcher-bin-[0-9.]+-[0-9]+-x86_64\.pkg\.tar\.zst$/.test(filename)) {
        archCommand.textContent = `curl --fail --location --retry 3 ${url.href} --output ${filename}\nsudo pacman -U ./${filename}`;
      }
    }
    for (const item of data.launchers || []) {
      const anchor = platforms.get(item.platform);
      setDownload(anchor, item);
      const label = anchor?.querySelector('span');
      if (label) label.textContent = `${item.label} · v${item.version}`;
    }
    const versions = [...new Set((data.launchers || []).filter(x => x.platform !== 'linux-arch-x64').map(x => x.version))];
    const note = document.querySelector('[data-launcher-version]');
    if (note) note.textContent = `Windows, macOS and Linux: ${versions.map(v => 'v'+v).join(', ')}. Arch Linux: v${data.launchers?.find(x => x.platform === 'linux-arch-x64')?.version || 'see download'}.`;
  }).catch(() => {
    status.textContent = 'Version information could not load. You can still use the download links below.';
  });
  getMetadata('/downloads/manifest.json').then(data => {
    const prism = data.packs?.prism;
    const multimc = data.packs?.multimc;
    if (!prism?.url || !multimc?.url) throw new Error('Community launcher packs unavailable');
    setDownload(packs?.querySelector('[data-pack="prism"]'), prism, `Download Prism pack · v${prism.version} (.mrpack)`);
    setDownload(packs?.querySelector('[data-pack="multimc"]'), multimc, `Download MultiMC pack · v${multimc.version} (.mrpack)`);
    const note = packs?.querySelector('.small');
    if (note) note.textContent = `Generated from signed client pack ${prism.version}.`;
  }).catch(() => {
    const note = packs?.querySelector('.small');
    if (note) note.textContent = 'Current pack information could not load. Try refreshing or use Aeromon Launcher above.';
  });
  async function detectPlatform() {
    const ua = navigator.userAgent;
    const platform = navigator.userAgentData?.platform || navigator.platform || '';
    let arch = /arm|aarch64/i.test(ua) ? 'arm' : '';
    try { arch = (await navigator.userAgentData?.getHighEntropyValues(['architecture']))?.architecture || arch; } catch {}
    if (/Android|iPhone|iPad|Mobile/i.test(ua) || (platform === 'MacIntel' && navigator.maxTouchPoints > 1)) return [null, 'Open this page on a Windows, macOS or Linux computer to install Aeromon.'];
    if (/Win/i.test(platform)) return arch === 'arm' ? [null, 'Choose a compatible computer below. Windows ARM builds are not available.'] : ['windows-x64'];
    if (/Mac/i.test(platform)) return arch === 'arm' ? ['macos-arm64'] : arch === 'x86' ? ['macos-x64'] : [null, 'Choose Apple Silicon or Intel below.'];
    if (/Linux/i.test(platform)) return arch === 'arm' ? [null, 'Linux ARM builds are not available.'] : ['linux-x64'];
    return [null, 'Choose your computer platform below.'];
  }
  Promise.all([detectPlatform(), launcherLoaded]).then(([[key, message]]) => {
    if (!key) { status.textContent = message; return; }
    const target = platforms.get(key);
    if (!target?.href) return;
    recommended.href = target.href;
    recommended.hidden = false;
    recommended.textContent = `Download for ${platformNames[key]}`;
    if (manifest.launchers) status.textContent = 'Choose your platform below. Launcher and client pack updates are managed separately.';
  });
})();
