import * as React from 'react';
import * as SliderPrimitive from '@radix-ui/react-slider';
import { cn } from '@/lib/utils';

const Slider = React.forwardRef<
  React.ElementRef<typeof SliderPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SliderPrimitive.Root> & { tone?: 'fire' | 'water' }
>(({ className, tone = 'fire', ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn(
      'relative flex w-full touch-none select-none items-center',
      className,
    )}
    {...props}
  >
    <SliderPrimitive.Track className="relative h-1.5 w-full grow overflow-hidden rounded-full bg-white/[0.08] shadow-[inset_0_1px_2px_rgb(0_0_0/0.4)]">
      <SliderPrimitive.Range className="fb-slider-range absolute h-full rounded-full" data-tone={tone} />
    </SliderPrimitive.Track>
    <SliderPrimitive.Thumb className="fb-slider-thumb block h-4 w-4 cursor-grab rounded-full border-2 border-[#fff1dc] bg-gradient-to-br from-[#ffd2a8] to-[#ff6a2b] shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:cursor-grabbing disabled:pointer-events-none disabled:opacity-50 data-[disabled]:opacity-50" />
  </SliderPrimitive.Root>
));
Slider.displayName = SliderPrimitive.Root.displayName;

export { Slider };
