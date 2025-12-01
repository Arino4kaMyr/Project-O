#!/bin/bash
# Скрипт для автоматической компиляции и запуска проекта

set -e

JASMIN_DIR="jasmin/generated"
JASMIN_JAR="$JASMIN_DIR/jasmin.jar"

echo "🔨 Компилирую Kotlin код..."
./gradlew build

echo ""
echo "📝 Генерирую Jasmin файлы..."
./gradlew run

echo ""
echo "⚙️  Компилирую .j файлы в .class..."

if [ ! -f "$JASMIN_JAR" ]; then
    echo "❌ Ошибка: $JASMIN_JAR не найден!"
    exit 1
fi

cd "$JASMIN_DIR"
for j_file in *.j; do
    if [ -f "$j_file" ]; then
        echo "  Компилирую $j_file..."
        java -jar jasmin.jar "$j_file" > /dev/null 2>&1 || {
            echo "❌ Ошибка компиляции $j_file"
            exit 1
        }
        echo "  ✓ $j_file скомпилирован"
    fi
done

cd - > /dev/null

echo ""
echo "🚀 Запускаю программу..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
java -cp "$JASMIN_DIR" Program

