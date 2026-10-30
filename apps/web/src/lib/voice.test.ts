import { describe, expect, it } from "vitest";
import { parseCommand } from "./voice";

describe("voice commands", () => {
  it("recognises whole-utterance commands", () => {
    expect(parseCommand("Next step")).toBe("next");
    expect(parseCommand("I'm done.")).toBe("next");
    expect(parseCommand("Hey LensQi, check this")).toBe("check");
    expect(parseCommand("take a photo please")).toBe("check");
    expect(parseCommand("go back")).toBe("back");
    expect(parseCommand("Say that again")).toBe("repeat");
    expect(parseCommand("skip this step")).toBe("skip");
    expect(parseCommand("stop talking")).toBe("mute");
    expect(parseCommand("Hold on")).toBe("pause");
    expect(parseCommand("pause please")).toBe("pause");
    expect(parseCommand("I'm back")).toBe("resume");
  });
  it("treats anything more as a question", () => {
    expect(parseCommand("is this the drain plug")).toBeNull();
    expect(parseCommand("what is next after the filter")).toBeNull();
    expect(parseCommand("check whether the camera is level")).toBeNull();
    expect(parseCommand("")).toBeNull();
  });
});
