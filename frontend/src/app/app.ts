import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { UpdateService } from './core/update.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {
  constructor() {
    // Aggiornamento automatico della PWA, attivo anche nella pagina di login
    inject(UpdateService).start();
  }
}
