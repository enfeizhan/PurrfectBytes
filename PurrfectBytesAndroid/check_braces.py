with open("app/src/main/java/com/purrfectbytes/android/ui/screens/MainScreen.kt") as f:
    text = f.read()
    stack = []
    for i, c in enumerate(text):
        if c == '{': stack.append(i)
        elif c == '}': 
            if stack: stack.pop()
            else: print(f"Extra closing brace at {i}")
    print(f"Remaining open braces: {len(stack)}")
