import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Piattaforma della macchina su cui installare l'hook. */
export type InstallerPlatform = 'unix' | 'windows';

interface PlatformFiles {
  /** Modello dello script di installazione, servito da /hooks (cartella hooks/ del repository). */
  template: string;
  /** Script dell'hook da incorporare nel modello. */
  hook: string;
  download: string;
}

const FILES: Record<InstallerPlatform, PlatformFiles> = {
  unix: { template: 'install.sh', hook: 'claude-monitor-hook.sh', download: 'claude-monitor-install.sh' },
  windows: { template: 'install.ps1', hook: 'claude-monitor-hook.ps1', download: 'claude-monitor-install.ps1' },
};

/** I valori finiscono tra apici singoli negli script: si accettano solo caratteri che non li possono chiudere. */
const SAFE_URL = /^https?:\/\/[A-Za-z0-9.\-:[\]]+$/;
const SAFE_KEY = /^[A-Za-z0-9_-]+$/;

/** Piattaforma del browser in uso: Windows oppure tutto il resto (Linux e macOS usano lo stesso script). */
export function detectPlatform(): InstallerPlatform {
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
  const platform = nav.userAgentData?.platform || nav.platform || nav.userAgent;
  return /win/i.test(platform) ? 'windows' : 'unix';
}

/**
 * Prepara lo script che installa l'hook su una macchina. Lo script viene composto nel browser,
 * perché la chiave in chiaro esiste solo qui e solo subito dopo la creazione.
 */
@Injectable({ providedIn: 'root' })
export class HookInstaller {
  private readonly http = inject(HttpClient);

  fileName(platform: InstallerPlatform): string {
    return FILES[platform].download;
  }

  /**
   * Comando che riscarica solo lo script dell'hook su una macchina già collegata: chiave e impostazioni
   * di Claude Code restano quelle presenti. Stringa vuota se l'indirizzo ha caratteri non ammessi.
   */
  updateCommand(platform: InstallerPlatform): string {
    const url = location.origin;
    if (!SAFE_URL.test(url)) {
      return '';
    }
    const source = `${url}/hooks/${FILES[platform].hook}`;
    if (platform === 'windows') {
      // Si antepone il BOM UTF-8: PowerShell 5.1 legge come UTF-8 solo i file che lo hanno
      return (
        `$b = (New-Object Net.WebClient).DownloadData('${source}'); ` +
        `[IO.File]::WriteAllBytes("$HOME\\.claude\\hooks\\${FILES.windows.hook}", [byte[]](0xEF,0xBB,0xBF) + $b)`
      );
    }
    const target = `~/.claude/hooks/${FILES.unix.hook}`;
    return `curl -fsS -o ${target} '${source}' && chmod +x ${target}`;
  }

  async download(platform: InstallerPlatform, key: string): Promise<void> {
    const files = FILES[platform];
    const url = location.origin;
    if (!SAFE_URL.test(url) || !SAFE_KEY.test(key)) {
      throw new Error('Indirizzo o chiave con caratteri non ammessi');
    }
    const [template, hook] = await Promise.all([this.text(files.template), this.text(files.hook)]);
    // Se un file manca, nginx risponde con la pagina dell'app: meglio fermarsi che scaricare uno script rotto
    const placeholders = ['__CLAUDE_MONITOR_URL__', '__CLAUDE_MONITOR_TOKEN__', '__CLAUDE_MONITOR_HOOK_SCRIPT__'];
    if (placeholders.some((p) => !template.includes(p)) || /^\s*</.test(hook)) {
      throw new Error('Modello dello script non disponibile');
    }
    // Sostituzioni con funzione: il contenuto può avere sequenze come "$&" che replace interpreterebbe
    let script = template
      .replace('__CLAUDE_MONITOR_URL__', () => url)
      .replace('__CLAUDE_MONITOR_TOKEN__', () => key)
      .replace('__CLAUDE_MONITOR_HOOK_SCRIPT__', () => hook.replace(/\s+$/, ''));
    if (platform === 'windows') {
      // PowerShell 5.1 legge come UTF-8 solo i file con BOM; fine riga di Windows
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
