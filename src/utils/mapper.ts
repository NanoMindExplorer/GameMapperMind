import TouchInjection from '../plugins/TouchInjection';

export class NativeGamepadMapper {
    private lastState: Record<string, boolean> = {};

    public async handleButton(buttonName: string, isDown: boolean, mapping: any, antiBanEnabled: boolean = false) {
        if (!mapping || typeof mapping.x !== 'number' || typeof mapping.y !== 'number') {
            const wasDown = this.lastState[buttonName] || false;
            // mock logic matching kotlin behavior
            this.lastState[buttonName] = isDown;
            return;
        }

        const wasDown = this.lastState[buttonName] || false;
        if (isDown && !wasDown) {
            let x = mapping.x;
            let y = mapping.y;
            if (antiBanEnabled) {
                x += Math.random() * 2 - 1;
                y += Math.random() * 2 - 1;
            }
            await TouchInjection.touchDown({ pointerId: 10, x, y });
        } else if (!isDown && wasDown) {
            await TouchInjection.touchUp({ pointerId: 10 });
        }
        this.lastState[buttonName] = isDown;
    }

    public async handleAxes(magnitude: number, cX: number, cY: number, deadzone = 0.15) {
        if (magnitude > deadzone) {
            await TouchInjection.touchMove({ pointerId: 11, x: cX + magnitude * 10, y: cY + magnitude * 10 });
        } else {
            // idle inside deadzone
        }
    }
}
