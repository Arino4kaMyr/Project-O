#!/usr/bin/env python3
"""
Простой скрипт для форматирования вывода судоку в таблицу 9x9
Показывает начальную и решенную сетку
Использование: ./gradlew run 2>&1 | grep -E '^[0-9-]+$' | python3 format-sudoku-simple.py
"""

import sys

def print_grid(label, grid):
    """Выводит сетку судоку в красивом формате"""
    print("\n" + "=" * 37)
    print(label)
    print("=" * 37)
    
    for i in range(9):
        # Добавляем разделители между блоками 3x3
        if i > 0 and i % 3 == 0:
            print("-----------------------------------")
        
        # Форматируем строку
        row_str = "| "
        for j in range(9):
            idx = i * 9 + j
            if idx < len(grid):
                val = grid[idx]
                # Заменяем 0 на точку для пустых клеток
                display_val = "." if val == 0 else val
            else:
                display_val = "."
            
            if j > 0 and j % 3 == 0:
                row_str += "| "
            row_str += f"{display_val:>2} "
        
        row_str += "|"
        print(row_str)
    
    print("=" * 37)
    print()

def format_sudoku():
    numbers = []
    current_grid = []
    grids = []
    
    # Читаем все числа из stdin
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        
        try:
            num = int(line)
            
            # Маркер начала первой сетки (начальная)
            if num == 100:
                if current_grid:
                    grids.append(current_grid)
                current_grid = []
                continue
            
            # Маркер начала второй сетки (решенная)
            if num == 200:
                if current_grid and len(current_grid) >= 81:
                    grids.append(current_grid[:81])
                current_grid = []
                continue
            
            # Пропускаем другие маркеры
            if num < 0 or (num >= 100 and num != 200):
                continue
            
            # Обычное число - добавляем в текущую сетку
            current_grid.append(num)
            
            # Если собрали 81 число - сохраняем сетку
            if len(current_grid) >= 81:
                grids.append(current_grid[:81])
                current_grid = []
                
        except ValueError:
            continue
    
    # Добавляем последнюю сетку, если есть
    if current_grid and len(current_grid) >= 81:
        grids.append(current_grid[:81])
    
    # Выводим сетки
    if len(grids) >= 1:
        print_grid("Начальная сетка:", grids[0])
    
    if len(grids) >= 2:
        print_grid("Решенная сетка:", grids[1])
    elif len(grids) == 1 and len(grids[0]) == 81:
        print("\nПримечание: Решенная сетка не найдена")

if __name__ == "__main__":
    format_sudoku()

