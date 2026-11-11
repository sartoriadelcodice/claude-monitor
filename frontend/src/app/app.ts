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
    // Automatic PWA update, active on the sign-in page too
    inject(UpdateService).start();
  }
}
