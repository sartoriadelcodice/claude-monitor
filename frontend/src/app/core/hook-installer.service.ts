import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Platform of the machine where the hook is installed. */
export type InstallerPlatform = 'unix' | 'windows';

interface PlatformFiles {
  /** Template of the installation script, served from /hooks (hooks/ folder of the repository). */
  template: string;
  /** Hook script to embed in the template. */
  hook: string;
  download: string;
}

const FILES: Record<InstallerPlatform, PlatformFiles> = {
  unix: { template: 'install.sh', hook: 'claude-monitor-hook.sh', download: 'claude-monitor-install.sh' },
  windows: { template: 'install.ps1', hook: 'claude-monitor-hook.ps1', download: 'claude-monitor-install.ps1' },
};

/** Values end up between single quotes in the scripts: only characters that cannot close them are accepted. */
const SAFE_URL = /^https?:\/\/[A-Za-z0-9.\-:[\]]+$/;
const SAFE_KEY = /^[A-Za-z0-9_-]+$/;

/** Platform of the current browser: Windows or everything else (Linux and macOS share the same script). */
export function detectPlatform(): InstallerPlatform {
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
  const platform = nav.userAgentData?.platform || nav.platform || nav.userAgent;
  return /win/i.test(platform) ? 'windows' : 'unix';
}

/**
 * Prepares the script that installs the hook on a machine. The script is composed in the browser,
 * because the clear-text key exists only here and only right after its creation.
 */
@Injectable({ providedIn: 'root' })
export class HookInstaller {
  private readonly http = inject(HttpClient);

  fileName(platform: InstallerPlatform): string {
    return FILES[platform].download;
  }

  /**
   * Command that downloads again only the hook script on an already connected machine: the key and the
   * Claude Code settings stay as they are. Empty string if the address has characters that are not allowed.
   */
  updateCommand(platform: InstallerPlatform): string {
    const url = location.origin;
    if (!SAFE_URL.test(url)) {
      return '';
    }
    const source = `${url}/hooks/${FILES[platform].hook}`;
    if (platform === 'windows') {
      // Prepend the UTF-8 BOM: PowerShell 5.1 reads as UTF-8 only the files that have it
      return (
        `$b = (New-Object Net.WebClient).DownloadData('${source}'); ` +
        `[IO.File]::WriteAllBytes("$HOME\\.claude\\hooks\\${FILES.windows.hook}", [byte[]](0xEF,0xBB,0xBF) + $b)`
      );
    }
    const target = `~/.claude/hooks/${FILES.unix.hook}`;
    return `curl -fsS -o ${target} '${source}' && chmod +x ${target}`;
  }

  /**
   * Command that downloads and starts the window focus agent on a Mac (claude-monitor-agent.sh).
   * It is a choice separate from the hook: the agent keeps running and must be enabled on purpose.
   * The same command updates it. Empty string if the address has characters that are not allowed.
   */
  agentCommand(): string {
    const url = location.origin;
    if (!SAFE_URL.test(url)) {
      return '';
    }
    const target = '~/.claude/hooks/claude-monitor-agent.sh';
    return `curl -fsS -o ${target} '${url}/hooks/claude-monitor-agent.sh' && bash ${target} install`;
  }

  async download(platform: InstallerPlatform, key: string): Promise<void> {
    const files = FILES[platform];
    const url = location.origin;
    if (!SAFE_URL.test(url) || !SAFE_KEY.test(key)) {
      throw new Error('Address or key with characters that are not allowed');
    }
    const [template, hook] = await Promise.all([this.text(files.template), this.text(files.hook)]);
    // If a file is missing nginx answers with the app page: better to stop than to download a broken script
    const placeholders = ['__CLAUDE_MONITOR_URL__', '__CLAUDE_MONITOR_TOKEN__', '__CLAUDE_MONITOR_HOOK_SCRIPT__'];
    if (placeholders.some((p) => !template.includes(p)) || /^\s*</.test(hook)) {
      throw new Error('Script template unavailable');
    }
    // Replacements with a function: the content may contain sequences like "$&" that replace would interpret
    let script = template
      .replace('__CLAUDE_MONITOR_URL__', () => url)
      .replace('__CLAUDE_MONITOR_TOKEN__', () => key)
      .replace('__CLAUDE_MONITOR_HOOK_SCRIPT__', () => hook.replace(/\s+$/, ''));
    if (platform === 'windows') {
      // PowerShell 5.1 reads as UTF-8 only files with a BOM; Windows line endings
      script = '\uFEFF' + script.replace(/\r?\n/g, '\r\n');
    }
    save(new Blob([script], { type: 'application/octet-stream' }), files.download);
  }

  private text(name: string): Promise<string> {
    return firstValueFrom(this.http.get(`hooks/${name}`, { responseType: 'text' }));
  }
}

function save(blob: Blob, name: string): void {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
}
